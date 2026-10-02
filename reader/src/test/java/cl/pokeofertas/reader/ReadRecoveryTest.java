package cl.pokeofertas.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadRecoveryTest {
    private JSONObject failure(String status, String access) throws Exception {
        return new JSONObject().put("status", status).put("accessKind", access).put("complete", false)
            .put("scopeUrl", ListingPolicy.START).put("products", new JSONArray());
    }
    @Test public void signInResetsOnceAndOnlyACompleteReadAllowsAnotherEpisode() throws Exception {
        ReadRecovery recovery = new ReadRecovery();
        JSONObject login = failure("blocked", "signin");
        assertEquals(ReadRecovery.Action.RESET_SESSION, recovery.next(login, ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(login, ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("timeout", ""), ListingPolicy.START));
        recovery.completeRead();
        assertEquals(ReadRecovery.Action.RESET_SESSION, recovery.next(login, ListingPolicy.START));
    }
    @Test public void wrongPageReloadCanLeadToOneSignInResetWithoutLoops() throws Exception {
        ReadRecovery recovery = new ReadRecovery();
        assertEquals(ReadRecovery.Action.RELOAD, recovery.next(failure("wrong_page", ""), ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("wrong_page", ""), ListingPolicy.START));
        assertEquals(ReadRecovery.Action.RESET_SESSION, recovery.next(failure("blocked", "signin"), ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("blocked", "signin"), ListingPolicy.START));
    }
    @Test public void verificationRateLimitsAndRejectedAccessNeverClearSession() throws Exception {
        ReadRecovery recovery = new ReadRecovery();
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("blocked", "verification"), ListingPolicy.START));
        for (int http : new int[] {401, 403, 429}) {
            assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("blocked", "signin").put("mainHttp", http), ListingPolicy.START));
        }
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("blocked", ""), ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("cancelled", ""), ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("storage_error", ""), ListingPolicy.START));
    }
    @Test public void temporaryFailuresAreBoundedAndCannotRecoverAnUnrelatedScope() throws Exception {
        ReadRecovery recovery = new ReadRecovery();
        JSONObject other = failure("blocked", "signin").put("scopeUrl", ListingPolicy.START.replace("550072427", "999"));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(other, ListingPolicy.START));
        assertEquals(ReadRecovery.Action.NONE, recovery.next(failure("blocked", "signin"), "https://evil.example/"));
        for (String status : new String[] {"network_error", "timeout", "incomplete", "parse_error", "navigation_blocked"}) {
            ReadRecovery episode = new ReadRecovery();
            assertEquals(ReadRecovery.Action.RELOAD, episode.next(failure(status, ""), ListingPolicy.START));
            assertEquals(ReadRecovery.Action.NONE, episode.next(failure(status, ""), ListingPolicy.START));
        }
        for (int http : new int[] {408, 500, 502, 503}) {
            assertEquals(ReadRecovery.Action.RELOAD, new ReadRecovery().next(failure("http_error", "").put("mainHttp", http), ListingPolicy.START));
        }
        assertEquals(ReadRecovery.Action.NONE, new ReadRecovery().next(failure("http_error", "").put("mainHttp", 404), ListingPolicy.START));
    }
    private JSONObject product(String key, int price) throws Exception {
        return new JSONObject().put("key", key).put("price", price).put("title", "Fixture offline")
            .put("url", "https://www.mercadolibre.cl/fixture/p/MLC123?pdp_filters=seller_id%3A550072427");
    }
    private JSONObject catalog(JSONObject... products) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject product : products) array.put(product);
        return new JSONObject().put("complete", true).put("status", "ok").put("scopeUrl", ListingPolicy.START).put("products", array);
    }
    @Test public void recoveryPreservesBaselineAndComparesNewProductsAndPricesOnlyAfterSuccess() throws Exception {
        JSONObject state = CatalogState.reconcile(new JSONObject(), catalog(product("old", 100)), ListingPolicy.START);
        JSONObject login = failure("blocked", "signin").put("products", new JSONArray().put(product("partial", 10)));
        ReadRecovery recovery = new ReadRecovery();
        assertEquals(ReadRecovery.Action.RESET_SESSION, recovery.next(login, ListingPolicy.START));
        assertSame(state, CatalogState.reconcile(state, login, ListingPolicy.START));
        JSONObject full = catalog(product("old", 80), product("new", 50));
        state = CatalogState.reconcile(state, full, ListingPolicy.START); recovery.completeRead();
        assertEquals(2, state.getJSONArray("pending").length());
        assertFalse(state.getJSONObject("seen").has("partial"));
        assertEquals(100, state.getJSONArray("pending").getJSONObject(0).getLong("previousPrice"));
        state = CatalogState.reconcile(state, full, ListingPolicy.START);
        assertEquals(2, state.getJSONArray("pending").length());
    }
}
