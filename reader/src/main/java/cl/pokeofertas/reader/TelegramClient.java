package cl.pokeofertas.reader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class TelegramClient {
    private final AppStore store;
    TelegramClient(AppStore store) { this.store = store; }
    private static final class TelegramError extends Exception {
        final boolean photoRejected;
        TelegramError(boolean photo) { super("Telegram rechazó la solicitud o no pudo conectar. Revisa la conexión del bot."); photoRejected = photo; }
    }
    private Object call(String token, String method, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new TelegramError(false);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] chunk = new byte[8192]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (buffer.size() + count > 2 * 1024 * 1024) throw new TelegramError(false);
                    buffer.write(chunk, 0, count);
                }
            }
            JSONObject response = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            if (code < 200 || code >= 300 || !response.optBoolean("ok")) {
                boolean rejected = "sendPhoto".equals(method) && code == 400 && java.util.regex.Pattern.compile(
                    "failed to get HTTP URL content|wrong type of the web page content|wrong file identifier/HTTP URL|IMAGE_PROCESS_FAILED|PHOTO_INVALID_DIMENSIONS|WEBPAGE_MEDIA_EMPTY|WEBPAGE_CURL_FAILED",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(response.optString("description")).find();
                throw new TelegramError(rejected);
            }
            return response.get("result");
        } catch (TelegramError e) { throw e; }
        catch (Exception e) { throw new TelegramError(false); }
        finally { if (connection != null) connection.disconnect(); }
    }
    void prepare(String token) throws Exception {
        if (!token.matches("\\d+:[A-Za-z0-9_-]+")) throw new Exception("El formato del token no es válido.");
        JSONObject bot = (JSONObject) call(token, "getMe", new JSONObject());
        String username = bot.optString("username");
        if (!username.matches("[A-Za-z0-9_]+")) throw new Exception("Telegram no devolvió un bot válido.");
        store.prepare(token, username, UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }
    void confirm() throws Exception {
        String token = store.pendingToken();
        JSONArray updates = (JSONArray) call(token, "getUpdates", new JSONObject().put("timeout", 0).put("allowed_updates", new JSONArray().put("message")));
        Set<Long> candidates = new HashSet<>();
        for (int i = 0; i < updates.length(); i++) {
            JSONObject message = updates.getJSONObject(i).optJSONObject("message");
            if (message == null) continue;
            JSONObject chat = message.optJSONObject("chat");
            if (chat != null && "private".equals(chat.optString("type")) && chat.optLong("id") > 0
                && ("/start " + store.setupCode()).equals(message.optString("text"))) candidates.add(chat.getLong("id"));
        }
        if (candidates.size() != 1) throw new Exception("Abre tu bot, envía el código indicado en privado y pulsa Confirmar conexión.");
        store.connect(candidates.iterator().next());
    }
    private static String image(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equals(uri.getScheme()) && java.util.Arrays.asList("http2.mlstatic.com", "http.mlstatic.com").contains(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getPath().startsWith("/D_") ? value : null;
        } catch (Exception e) { return null; }
    }
    static String caption(JSONObject product, boolean test, boolean missingPhoto) throws Exception {
        long price = product.optLong("price");
        String amount = price > 0 ? "$" + NumberFormat.getIntegerInstance(new Locale("es", "CL")).format(price) + " CLP" : "Precio no visible";
        String prefix = (test ? "🧪 Prueba con un producto real (no es una novedad)" : "🆕 Nueva publicación detectada") + "\n\n";
        String suffix = "\n💰 " + amount + (missingPhoto ? "\n📷 Foto no disponible." : "") + "\n\n" + product.getString("url");
        int budget = Math.min(700, 1024 - (prefix + suffix).codePointCount(0, (prefix + suffix).length()));
        if (budget < 1) throw new Exception("El enlace es demasiado largo para el mensaje con foto.");
        String title = product.getString("title"); int length = title.codePointCount(0, title.length());
        if (length > budget) title = title.substring(0, title.offsetByCodePoints(0, budget - 1)) + "…";
        return prefix + title + suffix;
    }
    void send(JSONObject product, boolean test) throws Exception {
        if (!store.connected()) throw new Exception("Conecta primero tu bot privado.");
        String url = product.optString("url");
        if (!ListingPolicy.product(url)) throw new Exception("El enlace del producto no es válido.");
        JSONObject markup = new JSONObject().put("inline_keyboard", new JSONArray().put(new JSONArray().put(new JSONObject().put("text", "🔗 Ver en Mercado Libre").put("url", url))));
        String photo = image(product.optString("imageUrl"));
        if (photo != null) {
            try {
                call(store.token(), "sendPhoto", new JSONObject().put("chat_id", store.chat()).put("photo", photo).put("caption", caption(product, test, false)).put("reply_markup", markup));
                return;
            } catch (TelegramError e) {
                if (!e.photoRejected) throw e;
                if (test) throw new Exception("Telegram no pudo cargar la foto de este producto. No se envió una prueba sin foto.");
            }
        } else if (test) throw new Exception("No hay una foto pública reconocida para este producto.");
        call(store.token(), "sendMessage", new JSONObject().put("chat_id", store.chat()).put("text", caption(product, false, true))
            .put("reply_markup", markup).put("link_preview_options", new JSONObject().put("is_disabled", true)));
    }
    void deliver(BooleanSupplier keepGoing) throws Exception {
        for (int i = 0; i < 5 && keepGoing.getAsBoolean(); i++) {
            JSONObject product = store.firstPending(); if (product == null) return;
            send(product, false); store.acknowledge(product.getString("key"));
        }
    }
}
