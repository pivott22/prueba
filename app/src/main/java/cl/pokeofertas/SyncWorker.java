package cl.pokeofertas;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import androidx.work.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

public class SyncWorker extends Worker {
    public SyncWorker(Context c, WorkerParameters p) { super(c,p); }
    public static void schedule(Context c) {
        WorkManager w = WorkManager.getInstance(c);
        if (Store.prefs(c).getString("server", "").isEmpty()) { w.cancelUniqueWork("seller-watch"); return; }
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        w.enqueueUniquePeriodicWork("seller-watch", ExistingPeriodicWorkPolicy.UPDATE,
            new PeriodicWorkRequest.Builder(SyncWorker.class,15,TimeUnit.MINUTES).setConstraints(constraints).build());
    }
    @Override public Result doWork() {
        Context c = getApplicationContext();
        String base = Store.prefs(c).getString("server", "");
        if (base.isEmpty()) return Result.success();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base.replaceAll("/+$", "") + "/products");
            if (!url.getProtocol().equals("https")) throw new IOException("El servidor debe usar HTTPS");
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000); conn.setReadTimeout(45000);
            conn.setRequestProperty("Authorization", "Bearer " + Store.prefs(c).getString("key", ""));
            if (conn.getResponseCode() != 200) {
                String detail = "Revisar acceso a Mercado Libre.";
                InputStream error = conn.getErrorStream();
                if(error != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(error, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line=reader.readLine();
                    if(line!=null && line.length()<4000) detail=new JSONObject(line).optString("error",detail);
                } catch(Exception ignored) { }
                throw new IOException("Servidor HTTP " + conn.getResponseCode() + ": " + detail);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] chunk = new byte[8192]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (buffer.size() + count > 2_000_000) throw new IOException("Respuesta demasiado grande");
                    buffer.write(chunk,0,count);
                }
            }
            byte[] bytes = buffer.toByteArray();
            JSONObject response = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!"550072427".equals(response.optString("seller_id"))) throw new IOException("Vendedor incorrecto");
            JSONArray incoming = response.getJSONArray("products");
            synchronized (Store.class) {
                JSONArray local = Store.items(c);
                for (int i=0;i<incoming.length();i++) {
                    JSONObject n = incoming.getJSONObject(i);
                    String id = n.getString("id");
                    if (!id.matches("MLC[0-9]+") || !Store.productUrl(n.optString("url")) || n.optLong("price") <= 0) continue;
                    JSONObject p = null;
                    for(int j=0;j<local.length();j++) if(id.equals(local.getJSONObject(j).optString("id"))) p=local.getJSONObject(j);
                    boolean fresh = p == null;
                    if (fresh) { p = new JSONObject().put("id",id).put("target",0); local.put(p); }
                    long old = p.optLong("price");
                    boolean wasDeal = Store.deal(p);
                    p.put("title",n.getString("title")).put("url",n.getString("url"))
                     .put("active",n.optBoolean("active",true)).put("shipping",0).put("shippingKnown",false).put("source","API");
                    Store.sample(p,n.getLong("price"));
                    boolean active = p.optBoolean("active",true);
                    boolean alert = active && ((fresh && Store.prefs(c).getBoolean("new",false))
                        || (old > p.optLong("price") && Store.prefs(c).getBoolean("drops",true))
                        || (!wasDeal && Store.deal(p)));
                    if(alert) notifyProduct(c,p,fresh ? "Producto nuevo" : "Precio actualizado");
                }
                Store.save(c, local);
            }
            String coverage = "partial".equals(response.optString("coverage")) ? " Cobertura parcial: " + response.optInt("listings_examined") + " publicaciones revisadas." : "";
            Store.prefs(c).edit().putString("status", "Última consulta: " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()) + ". Envío por confirmar." + coverage).apply();
            return Result.success();
        } catch(Exception e) {
            Store.prefs(c).edit().putString("status", "No se pudo consultar: " + e.getMessage()).apply();
            return Result.retry();
        } finally { if(conn != null) conn.disconnect(); }
    }
    @android.annotation.SuppressLint("MissingPermission")
    static void notifyProduct(Context c, JSONObject p, String reason) {
        NotificationManager m = c.getSystemService(NotificationManager.class);
        m.createNotificationChannel(new NotificationChannel("offers", "Precios Pokémon", NotificationManager.IMPORTANCE_DEFAULT));
        if(!m.areNotificationsEnabled()) return;
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(p.optString("url")));
        int id = p.optString("id").hashCode();
        PendingIntent pi = PendingIntent.getActivity(c,id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text = MainActivity.money(p.optLong("price")) + " · envío por confirmar";
        Notification notification = new Notification.Builder(c,"offers")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(reason + ": " + p.optString("title"))
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build();
        try { m.notify(id, notification); } catch(SecurityException ignored) { }
    }
}
