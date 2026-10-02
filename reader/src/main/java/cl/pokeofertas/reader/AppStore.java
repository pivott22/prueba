package cl.pokeofertas.reader;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class AppStore {
    private final SharedPreferences prefs;
    AppStore(Context context) { prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE); }
    String scope() { return prefs.getString("scope", ListingPolicy.START); }
    synchronized void scope(String url) throws Exception {
        if (!ListingPolicy.firstPage(url)) throw new Exception("Selecciona la primera página del listado del vendedor.");
        if (!ListingPolicy.withoutHash(scope()).equals(ListingPolicy.withoutHash(url))) {
            JSONObject state = state();
            state.put("seen", new JSONObject()).put("baseline", false);
            put("state", state);
        }
        if (!prefs.edit().putString("scope", url).commit()) throw new Exception("No se pudo guardar el listado.");
    }
    synchronized JSONObject json(String key) {
        try { return new JSONObject(prefs.getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    synchronized void put(String key, JSONObject value) {
        if (!prefs.edit().putString(key, value.toString()).commit()) throw new IllegalStateException("No se pudo guardar el historial en el teléfono.");
    }
    synchronized JSONObject state() {
        JSONObject state = json("state");
        try {
            if (!state.has("seen")) state.put("seen", new JSONObject());
            if (!state.has("pending")) state.put("pending", new JSONArray());
        } catch (Exception ignored) {}
        return state;
    }
    synchronized void reconcile(JSONObject scan) throws Exception {
        JSONObject previous = state(); JSONObject next = CatalogState.reconcile(previous, scan, scope());
        if (next != previous) put("state", next);
    }
    synchronized int pending() { JSONArray pending = state().optJSONArray("pending"); return pending == null ? 0 : pending.length(); }
    synchronized JSONObject firstPending() {
        JSONArray pending = state().optJSONArray("pending"); return pending == null ? null : pending.optJSONObject(0);
    }
    synchronized void acknowledge(String key) throws Exception {
        JSONObject state = state(); JSONArray pending = state.getJSONArray("pending");
        if (pending.length() > 0 && key.equals(pending.getJSONObject(0).optString("key"))) {
            pending.remove(0); put("state", state);
        }
    }
    boolean connected() { return prefs.contains("token") && prefs.getLong("chat", 0) > 0; }
    String token() throws Exception { return SecretBox.decrypt(prefs.getString("token", "")); }
    long chat() { return prefs.getLong("chat", 0); }
    void prepare(String token, String username, String code) throws Exception {
        if (!prefs.edit().putString("pendingToken", SecretBox.encrypt(token)).putString("username", username)
            .putString("code", code).putLong("setupExpires", System.currentTimeMillis() + 600000).commit()) throw new Exception("No se pudo guardar la conexión del bot.");
    }
    String setupCode() { return prefs.getString("code", ""); }
    String botUrl() { return "https://t.me/" + prefs.getString("username", "") + "?start=" + setupCode(); }
    String pendingToken() throws Exception {
        if (prefs.getLong("setupExpires", 0) < System.currentTimeMillis()) throw new Exception("El código expiró. Prepara la conexión otra vez.");
        return SecretBox.decrypt(prefs.getString("pendingToken", ""));
    }
    void connect(long chat) throws Exception {
        String token = pendingToken();
        if (!prefs.edit().putString("token", SecretBox.encrypt(token)).putLong("chat", chat)
            .remove("pendingToken").remove("code").remove("setupExpires").commit()) throw new Exception("No se pudo guardar la conexión del bot.");
    }
    void status(String value) { prefs.edit().putString("status", value).apply(); }
    String status() { return prefs.getString("status", "Abre la tienda y comprueba una lectura antes de activar los avisos."); }
}
