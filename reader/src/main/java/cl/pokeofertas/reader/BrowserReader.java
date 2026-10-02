package cl.pokeofertas.reader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

final class BrowserReader {
    private static BrowserReader instance;
    static synchronized BrowserReader get(Context context) {
        if (instance == null) instance = new BrowserReader(context.getApplicationContext());
        return instance;
    }
    final WebView view;
    final AppStore store;
    final Handler handler = new Handler(Looper.getMainLooper());
    Runnable listener;
    private final String script;
    private Consumer<JSONObject> callback;
    private int generation, attempts, stable, pages, mainHttp;
    private String expected, scope, signature;
    private boolean awaitingNavigation, navigationStarted;
    private final Set<String> visited = new HashSet<>();
    private final LinkedHashMap<String, JSONObject> products = new LinkedHashMap<>();
    private JSONObject lastSample;
    private String blockedDestination = "", browserIssue = "";
    private boolean changingSession;

    @SuppressLint("SetJavaScriptEnabled")
    private BrowserReader(Context context) {
        store = new AppStore(context);
        try (InputStream input = context.getAssets().open("probe.js")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            script = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Falta el lector de productos."); }
        view = new WebView(context);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                if (!request.isForMainFrame() || ListingPolicy.allowedBrowserPage(request.getUrl().toString())) return false;
                blockedDestination = ListingPolicy.publicLocation(request.getUrl().toString());
                browserIssue = "El navegador interno no pudo abrir " + blockedDestination + " Usa Abrir Mercado Libre en Chrome o copia el diagnóstico.";
                if (busy()) finish("navigation_blocked", browserIssue);
                store.status(browserIssue); changed();
                return true;
            }
            @Override public void onPageStarted(WebView web, String url, android.graphics.Bitmap icon) {
                blockedDestination = ""; browserIssue = "";
                if (busy() && awaitingNavigation) navigationStarted = true;
                if (busy() && !same(url, expected)) finish("blocked", "La tienda redirigió a otra página. Abre la app y revisa la tienda.");
                changed();
            }
            @Override public void onPageFinished(WebView web, String url) {
                if (busy() && awaitingNavigation && navigationStarted && same(url, expected)) {
                    awaitingNavigation = false; sample(generation);
                }
                // Persist only this app's normal WebView cookies. Nothing is copied from Chrome.
                Jobs.NETWORK.execute(() -> CookieManager.getInstance().flush());
                changed();
            }
            @Override public void onReceivedError(WebView web, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                browserIssue = "No se pudo cargar esta página. Revisa la conexión o prueba Abrir Mercado Libre en Chrome.";
                if (busy()) finish("network_error", browserIssue);
                else { store.status(browserIssue); changed(); }
            }
            @Override public void onReceivedHttpError(WebView web, WebResourceRequest request, WebResourceResponse response) {
                if (busy() && request.isForMainFrame()) {
                    mainHttp = response.getStatusCode();
                    if (mainHttp >= 400) finish("http_error", "Mercado Libre respondió HTTP " + mainHttp + ". La vigilancia se pausó.");
                }
            }
        });
    }
    boolean busy() { return callback != null; }
    boolean changingSession() { return changingSession; }
    void resetBrowserSession(Runnable complete) throws Exception {
        if (busy() || changingSession || WatchService.active()) throw new Exception("Pausa la vigilancia y espera a que termine la lectura antes de reiniciar la sesión.");
        changingSession = true; blockedDestination = ""; browserIssue = "";
        view.stopLoading(); view.loadUrl("about:blank"); changed();
        CookieManager.getInstance().removeAllCookies(removed -> {
            // These stores belong to this app's WebView, not Chrome or the bot preferences.
            WebStorage.getInstance().deleteAllData();
            view.clearCache(true); view.clearHistory();
            Jobs.NETWORK.execute(() -> CookieManager.getInstance().flush());
            changingSession = false;
            openStore();
            complete.run(); changed();
        });
    }
    String browserStatus() {
        return "Página de la app: " + ListingPolicy.publicLocation(view.getUrl())
            + (browserIssue.isEmpty() ? "" : "\n" + browserIssue);
    }
    JSONObject browserDiagnostic() throws Exception {
        return new JSONObject().put("page", ListingPolicy.publicLocation(view.getUrl()))
            .put("blockedDestination", blockedDestination).put("issue", browserIssue);
    }
    void changed() { if (listener != null) listener.run(); }
    static boolean same(String a, String b) { return ListingPolicy.withoutHash(a).equals(ListingPolicy.withoutHash(b)); }
    void openStore() {
        if (busy() || changingSession) return;
        view.loadUrl(store.scope());
    }
    void cancel() {
        if (busy()) finish("cancelled", "Lectura cancelada. Se conserva el historial.");
    }
    void scan(boolean reload, Consumer<JSONObject> result) {
        if (busy() || changingSession) { try { result.accept(new JSONObject().put("status", "busy").put("complete", false)); } catch (Exception ignored) {} return; }
        callback = result; generation++; scope = store.scope(); products.clear(); visited.clear(); pages = 0; mainHttp = 0;
        if (!reload && ListingPolicy.listing(view.getUrl()) && !same(scope, view.getUrl())) {
            finish("wrong_page", "Guarda la primera página que quieres vigilar antes de iniciar la lectura."); return;
        }
        page(scope, reload || !same(view.getUrl(), scope)); changed();
    }
    private void page(String url, boolean navigate) {
        if (!ListingPolicy.listing(url) || !visited.add(ListingPolicy.withoutHash(url))) {
            finish("incomplete", "La paginación no es válida. Se conserva el historial."); return;
        }
        generation++;
        expected = url; signature = null; stable = 0; attempts = 0; lastSample = null;
        awaitingNavigation = navigate; navigationStarted = false;
        int current = generation;
        if (navigate) {
            if (same(view.getUrl(), url)) view.reload(); else view.loadUrl(url);
            handler.postDelayed(() -> {
                if (busy() && generation == current && awaitingNavigation) finish("timeout", "La página tardó demasiado en cargar. Se conserva el historial.");
            }, 25000);
        } else sample(current);
    }
    private void sample(int current) {
        if (!busy() || current != generation) return;
        if (!same(view.getUrl(), expected)) { finish("blocked", "La página ya no es el listado guardado."); return; }
        view.evaluateJavascript(script, encoded -> {
            if (!busy() || current != generation) return;
            try {
                Object decoded = new JSONTokener(encoded).nextValue();
                if (!(decoded instanceof String)) throw new Exception();
                JSONObject data = new JSONObject((String) decoded); lastSample = data; attempts++;
                String status = data.optString("status");
                if ("blocked".equals(status) || "wrong_page".equals(status)) { finish(status, data.optString("reason")); return; }
                if (data.optBoolean("completeFirstPage")) {
                    String nextSignature = data.getJSONArray("products").toString() + data.optString("nextUrl");
                    stable = nextSignature.equals(signature) ? stable + 1 : 1; signature = nextSignature;
                    if (stable >= 3) { acceptPage(data, current); return; }
                } else { stable = 0; signature = null; }
                if (attempts >= 40) { finish("incomplete", data.optString("reason", "No se estabilizó el listado. Se conserva el historial.")); return; }
                handler.postDelayed(() -> sample(current), 500);
            } catch (Exception e) { finish("parse_error", "No se pudo interpretar el listado. Se conserva el historial."); }
        });
    }
    private void acceptPage(JSONObject data, int current) throws Exception {
        JSONArray found = data.getJSONArray("products");
        for (int i = 0; i < found.length(); i++) {
            JSONObject product = found.getJSONObject(i);
            if (!ListingPolicy.product(product.optString("url"))) { finish("incomplete", "Hay un enlace de producto no reconocido."); return; }
            products.put(product.getString("key"), product);
        }
        pages++;
        String next = data.isNull("nextUrl") ? "" : data.optString("nextUrl");
        if (next.isEmpty()) { finish("ok", "Lectura completa del listado encontrado."); return; }
        if (pages >= 10) { finish("incomplete", "El listado supera las 10 páginas. Se conserva el historial."); return; }
        handler.postDelayed(() -> { if (busy() && current == generation) { mainHttp = 0; page(next, true); } }, 1500);
    }
    private void finish(String status, String reason) {
        if (!busy()) return;
        Consumer<JSONObject> done = callback; callback = null; generation++;
        try {
            JSONObject result = new JSONObject().put("status", status).put("complete", "ok".equals(status))
                .put("reason", reason).put("checkedAt", Instant.now().toString()).put("scopeUrl", scope)
                .put("page", ListingPolicy.publicLocation(view.getUrl())).put("pages", pages)
                .put("source", "android_webview").put("androidVersion", Build.VERSION.RELEASE)
                .put("webViewVersion", WebView.getCurrentWebViewPackage() == null ? "unknown" : WebView.getCurrentWebViewPackage().versionName)
                .put("browser", browserDiagnostic())
                .put("mainHttp", mainHttp == 0 ? JSONObject.NULL : mainHttp);
            JSONArray list = new JSONArray(); for (JSONObject product : products.values()) list.put(product);
            result.put("products", list);
            if (lastSample != null) result.put("visibleCards", lastSample.optInt("visibleCards")).put("validProducts", lastSample.optInt("validProducts"));
            try { store.put("latest", result); }
            catch (IllegalStateException e) {
                result.put("status", "storage_error").put("complete", false).put("reason", "No se pudo guardar la lectura en el teléfono. Libera espacio y vuelve a probar.");
            }
            done.accept(result);
        } catch (Exception e) { store.status("No se pudo guardar la lectura. Vuelve a probar."); }
        changed();
    }
}
