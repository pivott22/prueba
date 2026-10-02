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
    @Test public void baselineIsSilentAndNewIdentifiersAreQueuedOnce() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 100)), ListingPolicy.START);
        assertEquals(0, state.getJSONArray("pending").length());
        state = CatalogState.reconcile(state, scan(product("a", 100), product("b", 50), product("b", 50)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
        assertEquals("b", state.getJSONArray("pending").getJSONObject(0).getString("key"));
        state = CatalogState.reconcile(state, scan(product("b", 50), product("a", 100)), ListingPolicy.START);
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
        state = CatalogState.reconcile(state, scan(product("b", 60), product("a", 100)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
        assertEquals("price_rise", state.getJSONArray("pending").getJSONObject(0).getString("type"));
        assertEquals(50, state.getJSONArray("pending").getJSONObject(0).getLong("previousPrice"));
        state = CatalogState.reconcile(state, scan(product("b", 60), product("a", 100)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
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
    @Test public void priceChangesAreQueuedOnceAndUseTheLatestValidReference() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 20000)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 18990), product("a", 18990)), ListingPolicy.START);
        JSONArray pending = state.getJSONArray("pending");
        assertEquals(1, pending.length());
        assertEquals("price_drop", pending.getJSONObject(0).getString("type"));
        assertEquals(20000, pending.getJSONObject(0).getLong("previousPrice"));
        assertEquals(18990, pending.getJSONObject(0).getLong("price"));
        state = new JSONObject(state.toString());
        state = CatalogState.reconcile(state, scan(product("a", 18990)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
        state = CatalogState.reconcile(state, scan(product("a", 21000)), ListingPolicy.START);
        assertEquals(2, state.getJSONArray("pending").length());
        assertEquals("price_rise", state.getJSONArray("pending").getJSONObject(1).getString("type"));
        assertEquals(18990, state.getJSONArray("pending").getJSONObject(1).getLong("previousPrice"));
        assertEquals(21000, state.getJSONArray("pending").getJSONObject(1).getLong("price"));
        state = CatalogState.reconcile(state, scan(product("a", 21000)), ListingPolicy.START);
        assertEquals(2, state.getJSONArray("pending").length());
        state = CatalogState.reconcile(state, scan(product("a", 20000)), ListingPolicy.START);
        assertEquals(3, state.getJSONArray("pending").length());
        assertEquals("price_drop", state.getJSONArray("pending").getJSONObject(2).getString("type"));
        assertEquals(21000, state.getJSONArray("pending").getJSONObject(2).getLong("previousPrice"));
    }
    @Test public void missingZeroInvalidPricesAndIncompleteReadsDoNotInventDrops() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 20000)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 10000)).put("complete", false), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 0)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 0).put("price", JSONObject.NULL)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 0).put("price", "100")), ListingPolicy.START);
        assertEquals(0, state.getJSONArray("pending").length());
        assertEquals(20000, state.getJSONObject("seen").getJSONObject("a").getLong("lastKnownPrice"));
        state = CatalogState.reconcile(state, scan(product("a", 18000)), ListingPolicy.START);
        assertEquals(1, state.getJSONArray("pending").length());
        assertEquals(20000, state.getJSONArray("pending").getJSONObject(0).getLong("previousPrice"));
    }
    @Test public void firstKnownPriceIsSilentAndOldHistoryCanDetectDrops() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 0)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 18000)), ListingPolicy.START);
        assertEquals(0, state.getJSONArray("pending").length());
        JSONObject old = new JSONObject().put("baseline", true).put("seen", new JSONObject().put("a", product("a", 20000))).put("pending", new JSONArray());
        state = CatalogState.reconcile(old, scan(product("a", 18000)), ListingPolicy.START);
        assertEquals("price_drop", state.getJSONArray("pending").getJSONObject(0).getString("type"));
        assertEquals(20000, state.getJSONArray("pending").getJSONObject(0).getLong("previousPrice"));
    }
    @Test public void queuedDropsKeepTheirOriginalPricesAndPhotoCaptionFits() throws Exception {
        JSONObject p = product("a", 18990).put("type", "price_drop").put("previousPrice", 20000).put("title", "🐱".repeat(800));
        String text = TelegramClient.caption(p, false, false);
        assertTrue(text.contains("Bajó el precio"));
        assertTrue(text.contains("Antes: $20.000 CLP"));
        assertTrue(text.contains("Ahora: $18.990 CLP"));
        assertTrue(text.contains("Bajó $1.010 CLP"));
        assertTrue(text.endsWith(p.getString("url")));
        assertTrue(text.codePointCount(0, text.length()) <= 1024);
        assertTrue(TelegramClient.caption(p, false, true).contains("Foto no disponible"));
        assertFalse(TelegramClient.caption(p, true, false).contains("Bajó el precio"));
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 20000)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 18000)), ListingPolicy.START);
        state = CatalogState.reconcile(state, scan(product("a", 17000)), ListingPolicy.START);
        assertEquals(18000, state.getJSONArray("pending").getJSONObject(0).getLong("price"));
        assertEquals(17000, state.getJSONArray("pending").getJSONObject(1).getLong("price"));
    }
    @Test public void risesKeepTheValidReferenceAcrossMissingPricesAndFailedReads() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), scan(product("a", 18000)), ListingPolicy.START);
        JSONObject failed = scan(product("a", 30000)).put("complete", false);
        assertSame(state, CatalogState.reconcile(state, failed, ListingPolicy.START));
        state = CatalogState.reconcile(state, scan(product("a", 0).put("price", JSONObject.NULL)), ListingPolicy.START);
        assertEquals(0, state.getJSONArray("pending").length());
        state = new JSONObject(state.toString());
        state = CatalogState.reconcile(state, scan(product("a", 20000), product("a", 20000)), ListingPolicy.START);
        JSONArray pending = state.getJSONArray("pending");
        assertEquals(1, pending.length());
        assertEquals("price_rise", pending.getJSONObject(0).getString("type"));
        assertEquals(18000, pending.getJSONObject(0).getLong("previousPrice"));
        state = CatalogState.reconcile(state, scan(product("a", 22000)), ListingPolicy.START);
        assertEquals(2, state.getJSONArray("pending").length());
        assertEquals(20000, state.getJSONArray("pending").getJSONObject(0).getLong("price"));
        assertEquals(20000, state.getJSONArray("pending").getJSONObject(1).getLong("previousPrice"));
    }
    @Test public void risePhotoCaptionShowsIncreaseAndKeepsTheFullLink() throws Exception {
        JSONObject p = product("a", 21000).put("type", "price_rise").put("previousPrice", 18990).put("title", "🐱".repeat(800));
        String text = TelegramClient.caption(p, false, false);
        assertTrue(text.contains("Subió el precio"));
        assertTrue(text.contains("Antes: $18.990 CLP"));
        assertTrue(text.contains("Ahora: $21.000 CLP"));
        assertTrue(text.contains("Subió $2.010 CLP"));
        assertFalse(text.contains("Bajó"));
        assertTrue(text.endsWith(p.getString("url")));
        assertTrue(text.codePointCount(0, text.length()) <= 1024);
        assertTrue(TelegramClient.caption(p, false, true).contains("Foto no disponible"));
        assertFalse(TelegramClient.caption(p, true, false).contains("Subió el precio"));
    }
}
