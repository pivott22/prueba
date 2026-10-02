package cl.pokeofertas.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;

final class CatalogState {
    private CatalogState() {}
    private static long price(JSONObject product, String field) {
        if (product == null || !(product.opt(field) instanceof Number)) return 0;
        double value = ((Number) product.opt(field)).doubleValue();
        return value > 0 && value <= 9007199254740991L && Math.rint(value) == value ? (long) value : 0;
    }
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
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.getJSONObject(i); String key = product.getString("key");
            unique.put(key, product);
        }
        for (java.util.Map.Entry<String, JSONObject> entry : unique.entrySet()) {
            String key = entry.getKey(); JSONObject product = entry.getValue();
            JSONObject previous = seen.optJSONObject(key);
            long currentPrice = price(product, "price"), previousPrice = price(previous, "lastKnownPrice");
            if (previousPrice == 0) previousPrice = price(previous, "price"); // Existing 0.1.0 history.
            if (baseline && previous == null) {
                pending.put(new JSONObject(product.toString()).put("type", "new_product"));
            } else if (baseline && previousPrice > 0 && currentPrice > 0 && currentPrice != previousPrice) {
                pending.put(new JSONObject(product.toString()).put("type", currentPrice < previousPrice ? "price_drop" : "price_rise")
                    .put("previousPrice", previousPrice));
            }
            JSONObject recorded = new JSONObject(product.toString());
            if (currentPrice > 0 || previousPrice > 0) recorded.put("lastKnownPrice", currentPrice > 0 ? currentPrice : previousPrice);
            seen.put(key, recorded);
        }
        return state.put("baseline", true).put("checkedAt", scan.optString("checkedAt"));
    }
}
