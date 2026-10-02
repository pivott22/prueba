package cl.pokeofertas.reader;

import org.json.JSONArray;
import org.json.JSONObject;

final class CatalogState {
    private CatalogState() {}
    static JSONObject reconcile(JSONObject original, JSONObject scan, String scope) throws Exception {
        if (!scan.optBoolean("complete") || !"ok".equals(scan.optString("status"))
            || !ListingPolicy.withoutHash(scan.optString("scopeUrl")).equals(ListingPolicy.withoutHash(scope))) return original;
        JSONArray products = scan.optJSONArray("products");
        if (products == null || products.length() == 0) return original;
        JSONObject state = new JSONObject(original.toString());
        if (!state.has("seen")) state.put("seen", new JSONObject());
        if (!state.has("pending")) state.put("pending", new JSONArray());
        JSONObject seen = state.getJSONObject("seen"); JSONArray pending = state.getJSONArray("pending");
        boolean baseline = state.optBoolean("baseline");
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.getJSONObject(i); String key = product.getString("key");
            if (baseline && !seen.has(key)) pending.put(new JSONObject(product.toString()));
            seen.put(key, product);
        }
        return state.put("baseline", true).put("checkedAt", scan.optString("checkedAt"));
    }
}
