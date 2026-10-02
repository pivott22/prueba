package cl.pokeofertas.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

public final class WatchService extends Service {
    private static final String CHANNEL = "watch";
    private static final int NOTICE = 21;
    private static volatile WatchService instance;
    private volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BrowserReader reader;
    private AppStore store;
    private PowerManager.WakeLock wakeLock;
    private long due;

    static boolean active() { WatchService service = instance; return service != null && service.running; }
    static void foregroundOpened() {
        WatchService service = instance;
        if (service != null && service.running) service.holdCpu();
    }
    @Override public void onCreate() {
        super.onCreate(); reader = BrowserReader.get(this); store = reader.store;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Vigilancia de la tienda", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":reader");
        wakeLock.setReferenceCounted(false);
    }
    private Notification notification(String text, boolean watching) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(watching ? "PokéOfertas: vigilancia activa" : "PokéOfertas: vigilancia pausada")
            .setContentText(text).setContentIntent(open).setOngoing(watching).setOnlyAlertOnce(true);
        if (watching) {
            PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, WatchService.class).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Pausar", pause).build());
        }
        return builder.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "pause".equals(intent.getAction())) { stopWatching("Vigilancia pausada por ti.", false); return START_NOT_STICKY; }
        startForeground(NOTICE, notification("Revisiones cada 2 minutos aproximadamente.", true));
        if (!store.connected()) { stopWatching("Conecta tu bot antes de activar la vigilancia.", true); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        instance = this; running = true; holdCpu();
        store.status("Vigilancia activa · Cada 2 min aprox. Mantén el teléfono conectado a Internet."); reader.changed();
        tick(false); return START_NOT_STICKY;
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    private void holdCpu() {
        if (wakeLock.isHeld()) wakeLock.release();
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }
    private void tick(boolean reload) {
        if (!running) return;
        if (reader.busy()) { handler.postDelayed(() -> tick(reload), 2000); return; }
        due = SystemClock.elapsedRealtime() + 120000;
        reader.scan(reload, scan -> {
            if (!running) return;
            if (!scan.optBoolean("complete")) { stopWatching(scan.optString("reason", "Lectura incompleta. Abre la tienda y vuelve a probar."), true); return; }
            Jobs.NETWORK.execute(() -> {
                String error = null;
                try { store.reconcile(scan); new TelegramClient(store).deliver(() -> running); }
                catch (Exception e) { error = e.getMessage(); }
                final String failure = error;
                handler.post(() -> {
                    if (!running) return;
                    if (failure != null) { stopWatching(failure + " Los avisos quedan pendientes.", true); return; }
                    store.status("Vigilancia activa · Cada 2 min aprox. · " + store.pending() + " avisos pendientes."); reader.changed();
                    getSystemService(NotificationManager.class).notify(NOTICE, notification("Última lectura: " + scan.optJSONArray("products").length() + " productos.", true));
                    handler.postDelayed(() -> tick(true), Math.max(1000, due - SystemClock.elapsedRealtime()));
                });
            });
        });
    }
    private void stopWatching(String reason, boolean notice) {
        running = false; handler.removeCallbacksAndMessages(null);
        store.status(reason); reader.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (notice) {
            try { getSystemService(NotificationManager.class).notify(NOTICE + 1, notification(reason, false)); }
            catch (SecurityException ignored) {}
        }
        reader.changed(); stopSelf();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        stopWatching("Android llegó al límite de vigilancia en segundo plano. Abre la app y vuelve a activarla.", true);
    }
    @Override public void onDestroy() {
        running = false; handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        if (reader != null) { reader.cancel(); reader.changed(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
