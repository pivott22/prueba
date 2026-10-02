package cl.pokeofertas.reader;

import org.json.JSONObject;

/** A bounded recovery episode ends only after a complete read of the saved store. */
final class ReadRecovery {
    enum Action { NONE, RELOAD, RESET_SESSION }
    static final long DELAY_MS = 10000;
    private boolean reloaded, reset;

    Action next(JSONObject scan, String scope) {
        if (scan.optBoolean("complete") || !ListingPolicy.firstPage(scope)
                || !ListingPolicy.withoutHash(scope).equals(ListingPolicy.withoutHash(scan.optString("scopeUrl")))) return Action.NONE;
        int http = scan.optInt("mainHttp");
        if (http == 401 || http == 403 || http == 429) return Action.NONE;
        String status = scan.optString("status"), access = scan.optString("accessKind");
        if ("verification".equals(access)) return Action.NONE;
        if ("signin".equals(access) && ("blocked".equals(status) || "wrong_page".equals(status))) {
            if (reset) return Action.NONE;
            reset = true; reloaded = true;
            return Action.RESET_SESSION;
        }
        if (reloaded || reset) return Action.NONE;
        boolean temporary = "wrong_page".equals(status) || "navigation_blocked".equals(status)
            || "network_error".equals(status) || "timeout".equals(status) || "incomplete".equals(status)
            || "parse_error".equals(status) || ("http_error".equals(status) && (http == 408 || http >= 500));
        if (!temporary) return Action.NONE;
        reloaded = true;
        return Action.RELOAD;
    }

    void completeRead() { reloaded = false; reset = false; }
}
