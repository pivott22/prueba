"""HTTPS-facing service. Deploy behind the hosting provider's TLS proxy."""
import hmac
import os
import secrets
import threading
import time
from flask import Flask, request, session, redirect, render_template_string, jsonify
import oauth
import server

app = Flask(__name__)
app.secret_key = os.environ.get("APP_API_KEY") or secrets.token_urlsafe(32)
app.config.update(SESSION_COOKIE_SECURE=True, SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE="Lax", MAX_CONTENT_LENGTH=8192)
PENDING = {}
PENDING_LOCK = threading.Lock()
PAGE = '''<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Conectar PokéOfertas</title>
<body><h1>Conectar PokéOfertas</h1><p>{{ message }}</p><p>Redirect URI para registrar en Mercado Libre:</p><pre>{{ uri }}</pre>
<form method="post" action="/oauth/start"><label>Clave privada del servidor <input name="key" type="password" required autocomplete="off"></label><button>Autorizar mi cuenta</button></form>
<h2>Diagnosticar conexión</h2><p>Comprueba el acceso a tu cuenta, tus publicaciones y al vendedor 550072427. No muestra claves ni datos de tu cuenta.</p>
<form method="post" action="/diagnostic"><label>Clave privada del servidor <input name="key" type="password" required autocomplete="off"></label><p><label>Publicación individual de prueba <input name="item_id" value="MLC4261910624" maxlength="23"></label></p><button>Diagnosticar conexión</button></form>
<p>Los permisos de tu cuenta no garantizan acceso a publicaciones de otros vendedores. Esta conexión permitirá comprobarlo.</p></body></html>'''


def key_matches(value):
    expected = os.environ.get("APP_API_KEY", "")
    return bool(expected) and hmac.compare_digest(value.encode(), expected.encode())


@app.after_request
def protect(response):
    response.headers["Cache-Control"] = "no-store"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Content-Security-Policy"] = "default-src 'none'; form-action 'self' https://*.mercadolibre.cl https://mercadolibre.cl; frame-ancestors 'none'; base-uri 'none'"
    return response


@app.get("/health")
def health():
    return jsonify(status="server-running")


@app.get("/")
def home():
    try:
        uri = oauth.redirect_uri()
    except RuntimeError as error:
        uri = str(error)
    return render_template_string(PAGE, uri=uri, message="Configura la aplicación y después autoriza el acceso desde esta página.")


@app.post("/oauth/start")
def start():
    if not key_matches(request.form.get("key", "")):
        return jsonify(error="Clave privada incorrecta."), 401
    try:
        link, state, verifier = oauth.begin_authorization()
        browser = secrets.token_urlsafe(32)
        with PENDING_LOCK:
            for ident in list(PENDING):
                if PENDING[ident]["expires"] < time.time():
                    del PENDING[ident]
            # One active authorization per service avoids overwriting another operator's tokens.
            if PENDING:
                return jsonify(error="Hay una autorización en curso. Espera a que termine o expire en 10 minutos."), 409
            PENDING[state] = {"verifier": verifier, "browser": browser, "expires": time.time() + 600}
        session["oauth_browser"] = browser
        return redirect(link, code=303)
    except RuntimeError as error:
        return jsonify(error=str(error)), 400


@app.get("/oauth/callback")
def callback():
    state = request.args.get("state", "")
    with PENDING_LOCK:
        pending = PENDING.get(state)
        browser = session.get("oauth_browser", "")
        if not pending or pending["expires"] < time.time() or not browser or not hmac.compare_digest(browser, pending["browser"]):
            return jsonify(error="La autorización expiró o no corresponde a este navegador. Inicia nuevamente desde la página del servidor."), 400
        del PENDING[state]
    session.pop("oauth_browser", None)
    if request.args.get("error") or not request.args.get("code"):
        return jsonify(error="La autorización no fue concedida. Puedes intentarlo nuevamente."), 400
    try:
        with oauth.TOKEN_LOCK:
            data = oauth.token_request({"grant_type": "authorization_code", "code": request.args["code"],
                                       "redirect_uri": oauth.redirect_uri(), "code_verifier": pending["verifier"]})
            oauth.save_tokens(data)
        with server.LOCK:
            server.CACHE.update(at=0, data=None)
        return "<!doctype html><html lang='es'><meta charset='utf-8'><title>Cuenta conectada</title><body><h1>Cuenta conectada</h1><p>Ya puedes configurar la dirección y clave del servidor en PokéOfertas y pulsar Consultar ahora.</p><p>Esta autorización no confirma acceso al vendedor 550072427; la primera consulta lo comprobará.</p></body></html>"
    except RuntimeError as error:
        return jsonify(error=str(error)), 502


@app.get("/products")
def products():
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer ") or not key_matches(authorization[7:]):
        return jsonify(error="Clave del servidor incorrecta."), 401
    try:
        return jsonify(server.collect())
    except RuntimeError as error:
        return jsonify(error=str(error)), 502
    except Exception:
        return jsonify(error="La respuesta de Mercado Libre no cumple el formato esperado."), 502


@app.post("/diagnostic")
def diagnostic():
    if not key_matches(request.form.get("key", "")):
        return jsonify(error="Clave privada incorrecta."), 401
    try:
        return jsonify(server.diagnose_connection(request.form.get("item_id", "").strip()))
    except ValueError as error:
        return jsonify(error=str(error)), 400
    except Exception:
        return jsonify(error="No se pudo completar el diagnóstico."), 502
