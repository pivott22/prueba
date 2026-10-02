package cl.pokeofertas.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReaderStateTest {
    private JSONObject product(String key, long price) throws Exception {
        return new JSONObject().put("key", key).put("price", price).put("title", "Fixture de prueba")
            .put("url", "https://www.mercadolibre.cl/fixture/p/MLC123?pdp_filters=seller_id%3A550072427");
    }
    private JSONObject scan(JSONObject... products) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject p : products) array.put(p);
        return new JSONObject().put("status", "ok").put("complete", true).put("scopeUrl", ListingPolicy.START).put("products", array);
    }
    @Test public void baselineIsSilentAndOnlyNewIdentifiersAreQueuedOnce() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 100)), ListingPolicy.START);
        assertEquals(0, state.getJSONArray("pending").length());
        state = CatalogState.reconcile(state, scan(product("a", 90), product("b", 50), product("b", 50)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
        assertEquals("b", state.getJSONArray("pending").getJSONObject(0).getString("key"));
        state = CatalogState.reconcile(state, scan(product("b", 45), product("a", 90)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
    }
    @Test public void failedEmptyAndForeignScopeReadsPreserveHistoryAndOutbox() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 100)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 100), product("b", 10)), ListingPolicy.START);
        assertSame(state, CatalogState.reconcile(state, scan(product("c", 15)).put("complete", false), ListingPolicy.START));
        assertSame(state, CatalogState.reconcile(state, scan(), ListingPolicy.START));
        assertSame(state, CatalogState.reconcile(state, scan(product("c", 15)).put("status", "blocked"), ListingPolicy.START));
        assertSame(state, CatalogState.reconcile(state, scan(product("c", 15)).put("scopeUrl", ListingPolicy.START + "_Other"), ListingPolicy.START));
        assertEquals(1, state.getJSONArray("pending").length());
    }
    @Test public void reappearingProductsAreNotNewAndStateSurvivesSerialization() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 100), product("b", 50)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 100)), ListingPolicy.START);
        state = new JSONObject(state.toString());
        state = CatalogState.reconcile(state, scan(product("b", 60), product("a", 90)), ListingPolicy.START);
        assertEquals(0, state.getJSONArray("pending").length());
    }
    @Test public void rejectsForeignSellerCredentialsPortsAndNonHttps() {
        assertTrue(ListingPolicy.firstPage(ListingPolicy.START));
        assertFalse(ListingPolicy.listing(ListingPolicy.START.replace("550072427", "550072428")));
        assertFalse(ListingPolicy.listing(ListingPolicy.START.replace("https:", "http:")));
        assertFalse(ListingPolicy.listing(ListingPolicy.START.replace("https://", "https://user@")));
        assertFalse(ListingPolicy.listing(ListingPolicy.START.replace(".cl/", ".cl:444/")));
        assertFalse(ListingPolicy.firstPage(ListingPolicy.START.replace("?", "_Desde_49?")));
        assertFalse(ListingPolicy.product("https://evil.example/p/MLC123"));
        assertEquals("https://www.mercadolibre.cl/gz/account-verification", ListingPolicy.publicLocation("https://www.mercadolibre.cl/gz/account-verification?go=private&tid=hidden#token"));
    }
    @Test public void photoCaptionPreservesLinkAndFitsUnicodeLimit() throws Exception {
        JSONObject p = product("a", 18990).put("title", "🐱".repeat(800));
        String text = TelegramClient.caption(p, true, false);
        assertTrue(text.codePointCount(0, text.length()) <= 1024);
        assertTrue(text.endsWith(p.getString("url")));
        assertTrue(text.contains("no es una novedad"));
        assertTrue(text.contains("18.990"));
    }
}
