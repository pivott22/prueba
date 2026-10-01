"""Server-side OAuth, PKCE and encrypted token persistence."""
import base64
import hashlib
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from cryptography.fernet import Fernet, InvalidToken

TOKEN_LOCK = threading.RLock()


def redirect_uri():
    base = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
    if not base:
        host = os.environ.get("RENDER_EXTERNAL_HOSTNAME", "")
        if host:
            base = "https://" + host
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise RuntimeError("Configura PUBLIC_BASE_URL con la dirección HTTPS real del servidor.")
    return base + "/oauth/callback"


def cipher():
    key = os.environ.get("TOKEN_ENCRYPTION_KEY", "")
    if len(key) < 32:
        raise RuntimeError("Configura TOKEN_ENCRYPTION_KEY con al menos 32 caracteres aleatorios.")
    derived = base64.urlsafe_b64encode(hashlib.sha256(key.encode()).digest())
    return Fernet(derived)


def token_file():
    return Path(os.environ.get("TOKEN_FILE", "/tmp/pokeofertas-tokens.enc"))


def read_tokens():
    path = token_file()
    if not path.exists():
        return None
    try:
        return json.loads(cipher().decrypt(path.read_bytes()))
    except (InvalidToken, ValueError, OSError):
        raise RuntimeError("No se pudo leer la autorización guardada. Vuelve a conectar la cuenta.") from None


def save_tokens(data):
    if not isinstance(data.get("access_token"), str) or not data.get("access_token") or not data.get("refresh_token"):
        raise RuntimeError("Mercado Libre no entregó los tokens requeridos.")
    lifetime = int(data.get("expires_in", 0))
    if lifetime <= 0:
        raise RuntimeError("Mercado Libre no entregó una duración de acceso válida.")
    saved = {"access_token": data["access_token"], "refresh_token": data["refresh_token"],
             "expires_at": time.time() + lifetime, "user_id": data.get("user_id")}
    encrypted = cipher().encrypt(json.dumps(saved).encode())
    path = token_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    fd = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(encrypted)
    temporary.replace(path)
    return saved


def token_request(fields):
    client_id = os.environ.get("MELI_CLIENT_ID", "")
    secret = os.environ.get("MELI_CLIENT_SECRET", "")
    if not client_id or not secret:
        raise RuntimeError("Faltan MELI_CLIENT_ID y MELI_CLIENT_SECRET en la configuración privada del servidor.")
    fields = dict(fields, client_id=client_id, client_secret=secret)
    request = urllib.request.Request("https://api.mercadolibre.com/oauth/token",
        data=urllib.parse.urlencode(fields).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
            raw = response.read(100001)
            if len(raw) > 100000:
                raise RuntimeError("Respuesta de autorización demasiado grande.")
            return json.loads(raw)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Autorización HTTP {error.code}. Revisa los datos de la aplicación y vuelve a conectar.") from None
    except (urllib.error.URLError, TimeoutError, ValueError):
        raise RuntimeError("No se pudo completar la autorización con Mercado Libre.") from None


def access_token():
    with TOKEN_LOCK:
        saved = read_tokens()
        if saved is None:
            fallback = os.environ.get("MELI_ACCESS_TOKEN", "")
            if fallback:
                return fallback
            raise RuntimeError("Conecta tu cuenta desde la página de autorización del servidor.")
        if saved["expires_at"] <= time.time() + 120:
            saved = save_tokens(token_request({"grant_type": "refresh_token", "refresh_token": saved["refresh_token"]}))
        return saved["access_token"]


def begin_authorization():
    client_id = os.environ.get("MELI_CLIENT_ID", "")
    if not client_id:
        raise RuntimeError("Todavía falta configurar MELI_CLIENT_ID.")
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(32)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    fields = {"response_type": "code", "client_id": client_id, "redirect_uri": redirect_uri(),
              "state": state, "code_challenge": challenge, "code_challenge_method": "S256", "scope": "read offline_access"}
    return "https://auth.mercadolibre.cl/authorization?" + urllib.parse.urlencode(fields), state, verifier
