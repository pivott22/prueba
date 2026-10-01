"""Private seller price adapter. Use requirements.txt for OAuth support."""
import hmac
import json
import os
import re
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SELLER = "550072427"
API = "https://api.mercadolibre.com"
LOCK = threading.Lock()
CACHE = {"at": 0, "data": None}


def pokemon_title(title):
    plain = "".join(c for c in unicodedata.normalize("NFKD", str(title)) if not unicodedata.combining(c))
    return bool(re.search(r"\bpokemon\b", plain, re.I))


def normalize_item(item):
    """Never accept other sellers, currencies, external URLs or invalid prices."""
    if str(item.get("seller_id")) != SELLER or item.get("currency_id") != "CLP":
        return None
    if not pokemon_title(item.get("title", "")):
        return None
    price = item.get("price")
    if isinstance(price, bool) or not isinstance(price, (int, float)) or price <= 0 or price > 10_000_000_000 or price != int(price):
        return None
    ident = str(item.get("id", ""))
    if not re.fullmatch(r"MLC[0-9]+", ident):
        return None
    link = str(item.get("permalink", ""))
    parsed = urllib.parse.urlparse(link)
    host = parsed.hostname or ""
    if parsed.scheme != "https" or parsed.username or not (host == "mercadolibre.cl" or host.endswith(".mercadolibre.cl")):
        return None
    return {"id": ident, "title": item["title"], "price": int(price), "url": link,
            "active": item.get("status") == "active", "shipping_known": False}


def api_get(path):
    from oauth import access_token
    token = access_token()
    request = urllib.request.Request(API + path, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise RuntimeError("Respuesta de Mercado Libre demasiado grande.")
            return json.loads(raw)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise RuntimeError(f"Mercado Libre HTTP {error.code}: token vencido o acceso al vendedor no autorizado.") from None
        if error.code == 429:
            raise RuntimeError("Mercado Libre limitó las consultas. Intentar más tarde.") from None
        raise RuntimeError(f"Mercado Libre HTTP {error.code}.") from None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        raise RuntimeError("No se pudo leer la API de Mercado Libre.") from None


def collect():
    with LOCK:
        if CACHE["data"] is not None and time.time() - CACHE["at"] < 300:
            return CACHE["data"]
        ids = []
        offset = 0
        total = 0
        # Limited to 500 seller listings per synchronization; never claim full coverage.
        while offset < 500:
            listing = api_get(f"/users/{SELLER}/items/search?status=active&limit=100&offset={offset}")
            results = listing.get("results", [])
            if not isinstance(results, list):
                raise RuntimeError("Formato inesperado en búsqueda de vendedor.")
            total = int(listing.get("paging", {}).get("total", len(results)))
            ids.extend(str(i) for i in results if re.fullmatch(r"MLC[0-9]+", str(i)))
            offset += len(results)
            if not results or offset >= total:
                break
        products = []
        for start in range(0, len(ids), 20):
            query = urllib.parse.urlencode({"ids": ",".join(ids[start:start+20])})
            batch = api_get("/items/bulk?" + query)
            if not isinstance(batch, list):
                raise RuntimeError("Formato inesperado en consulta de productos.")
            for result in batch:
                code = result.get("status_code", result.get("code", 0))
                if code != 200:
                    raise RuntimeError(f"Consulta incompleta: un producto devolvió HTTP {code}.")
                item = normalize_item(result.get("body", {}))
                if item is not None:
                    products.append(item)
        data = {"seller_id": SELLER, "products": products, "checked_at": datetime.now(timezone.utc).isoformat(),
                "coverage": "partial" if offset < total else "seller-active-listings",
                "listings_examined": len(ids), "listings_total": total,
                "note": "Filtrado por título Pokémon. Envío no calculado. No es una tasación del mercado."}
        CACHE.update(at=time.time(), data=data)
        return data


class Handler(BaseHTTPRequestHandler):
    def respond(self, code, data):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        expected = "Bearer " + os.environ.get("APP_API_KEY", "")
        received = self.headers.get("Authorization", "")
        if not os.environ.get("APP_API_KEY") or not hmac.compare_digest(received.encode(), expected.encode()):
            self.respond(401, {"error": "Clave del servidor incorrecta."})
            return
        if self.path == "/health":
            self.respond(200, {"status": "server-running", "seller_id": SELLER,
                               "meli_token_configured": bool(os.environ.get("MELI_ACCESS_TOKEN")),
                               "market_access_verified": CACHE["data"] is not None})
            return
        if self.path != "/products":
            self.respond(404, {"error": "Recurso no encontrado."})
            return
        try:
            self.respond(200, collect())
        except RuntimeError as error:
            self.respond(502, {"error": str(error)})
        except Exception:
            self.respond(502, {"error": "La respuesta no cumple el formato esperado. Revisar integración."})

    def log_message(self, format, *args):
        # Do not log request paths, credentials or upstream bodies.
        pass


if __name__ == "__main__":
    if not os.environ.get("APP_API_KEY"):
        raise SystemExit("Configura APP_API_KEY antes de iniciar.")
    ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()
