package cl.pokeofertas;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

public final class Store {
    public static final String SELLER_URL = "https://listado.mercadolibre.cl/pokemon_CustId_550072427_NoIndex_True?sb=seller_id";
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("watch", Context.MODE_PRIVATE); }
    static synchronized JSONArray items(Context c) {
        try { return new JSONArray(prefs(c).getString("items", "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    static synchronized void save(Context c, JSONArray a) { prefs(c).edit().putString("items", a.toString()).commit(); }
    static void sample(JSONObject p, long value) throws JSONException {
        JSONArray h = p.optJSONArray("history");
        if (h == null) h = new JSONArray();
        if (h.length() == 0 || h.getJSONObject(h.length()-1).optLong("price") != value) {
            h.put(new JSONObject().put("price", value).put("at", System.currentTimeMillis()));
        }
        while (h.length() > 60) h.remove(0);
        p.put("history", h).put("price", value).put("checked", System.currentTimeMillis());
    }
    static boolean deal(JSONObject p) {
        return p.optBoolean("active", true) && p.optLong("target") > 0 && p.optLong("price") > 0
            && p.optLong("price") + p.optLong("shipping", 0) <= p.optLong("target");
    }
    static boolean productUrl(String raw) {
        try {
            java.net.URI u = new java.net.URI(raw);
            String h = u.getHost();
            return "https".equals(u.getScheme()) && h != null && u.getUserInfo() == null
                && (h.equals("mercadolibre.cl") || h.endsWith(".mercadolibre.cl"));
        } catch (Exception e) { return false; }
    }
}
