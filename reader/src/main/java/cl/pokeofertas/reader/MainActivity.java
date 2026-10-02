package cl.pokeofertas.reader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private BrowserReader reader;
    private AppStore store;
    private LinearLayout layout, browserHost;
    private TextView status, latest, browserStatus;
    private final List<Button> controls = new ArrayList<>();
    private boolean networkBusy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };
    private interface Task { String run() throws Exception; }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reader = BrowserReader.get(this); store = reader.store;
        ScrollView scroll = new ScrollView(this);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(20)); layout.setBackgroundColor(Color.rgb(241, 250, 250));
        scroll.addView(layout); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        text("PokéOfertas", 30);
        text("Lector del teléfono → tu bot privado de Telegram", 17);
        text("Vendedor 550072427 · Pokémon · Nuevos productos, bajadas y subidas de precio", 15);
        status = text("", 16);
        text("1. Comprueba la tienda", 21);
        control("Abrir tienda", () -> reader.openStore());
        control("Abrir Mercado Libre en Chrome", this::openStoreInChrome);
        control("Reiniciar sesión del navegador interno", this::resetBrowserSession);
        control("Guardar esta primera página", () -> {
            try { store.scope(reader.view.getUrl()); message("Listado guardado. Al activar la vigilancia se registrarán los productos existentes sin avisos."); }
            catch (Exception e) { message(e.getMessage()); }
        });
        control("Probar lectura sin avisos", () -> reader.scan(false, scan -> {
            message(scan.optBoolean("complete") ? "Lectura completa: " + scan.optJSONArray("products").length() + " productos. Ahora puedes probar la foto y activar la vigilancia." : scan.optString("reason"));
        }));
        text("Chrome permite abrir la tienda e iniciar sesión en el navegador del teléfono. Su sesión no se transfiere a esta app. Para activar avisos, la lectura de este navegador interno debe funcionar. Si pide iniciar sesión, complétalo aquí y vuelve a Abrir tienda.", 14);
        browserStatus = text("", 13);
        browserHost = new LinearLayout(this);
        layout.addView(browserHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440)));
        text("2. Conecta Telegram", 21);
        control("Conectar mi bot", this::connectionDialog);
        control("Enviar prueba con foto de un producto real", this::photoTest);
        text("3. Vigila las novedades", 21);
        text("La primera lectura completa guarda el listado sin avisar. Después avisa de productos nuevos y de cualquier bajada o subida respecto del último precio válido leído. Los avisos de precio muestran el anterior, el nuevo y la diferencia, con foto y enlace cuando están disponibles.", 15);
        text("Si la tienda pide iniciar sesión durante la vigilancia, se reinicia automáticamente la sesión de este navegador una vez y se vuelve al listado. Puede cerrar tu sesión de Mercado Libre. Si el acceso sigue fallando, la vigilancia se pausa y te avisa. Una página distinta o un fallo temporal se intenta recargar una vez. Se conservan el bot, el historial y los avisos pendientes.", 14);
        control("Activar vigilancia cada 2 min", this::activate);
        Button pause = new Button(this); pause.setText("Pausar vigilancia"); layout.addView(pause);
        pause.setOnClickListener(v -> { stopService(new Intent(this, WatchService.class)); store.status("Vigilancia pausada por ti."); update(); });
        control("Reintentar avisos pendientes", () -> {
            if (!store.connected()) { message("Conecta primero tu bot."); return; }
            io(() -> { new TelegramClient(store).deliver(() -> true); return "Reintento terminado. Quedan " + store.pending() + " avisos pendientes."; });
        });
        text("El teléfono debe seguir encendido y conectado a Internet; el PC puede estar apagado. Android puede retrasar consultas. En Android 15/16 hay un límite de 6 horas en segundo plano por cada 24 horas para este servicio: abre la app para reiniciar ese tiempo y vuelve a activar si se pausó.", 14);
        text("Última lectura", 21); latest = text("Todavía no hay una lectura.", 15);
        control("Ver productos leídos", this::showProducts);
        Button diagnostic = new Button(this); diagnostic.setText("Copiar diagnóstico sin claves"); layout.addView(diagnostic);
        diagnostic.setOnClickListener(v -> {
            try {
                JSONObject diagnosticData = store.json("latest").put("browser", reader.browserDiagnostic());
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Diagnóstico de la tienda", diagnosticData.toString()));
                message("Diagnóstico copiado. No contiene el token de Telegram ni cookies.");
            } catch (Exception e) { message("No se pudo copiar el diagnóstico."); }
        });
        Button battery = new Button(this); battery.setText("Abrir ajustes de ahorro de batería"); layout.addView(battery);
        battery.setOnClickListener(v -> { try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception e) { message("Abre los ajustes de batería del teléfono."); } });
        attachBrowser();
        if (reader.view.getUrl() == null) reader.openStore();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(21, 42, 50));
        view.setPadding(0, dp(8), 0, dp(8)); layout.addView(view); return view;
    }
    private void control(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); layout.addView(button); controls.add(button);
        button.setOnClickListener(v -> { if (WatchService.active()) { message("Pausa la vigilancia antes de cambiar la tienda o hacer pruebas."); return; } action.run(); update(); });
    }
    private void attachBrowser() {
        if (reader.view.getParent() instanceof ViewGroup) ((ViewGroup) reader.view.getParent()).removeView(reader.view);
        browserHost.addView(reader.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    @Override public void onStart() { super.onStart(); attachBrowser(); reader.listener = this::update; handler.post(refresh); }
    @Override public void onResume() {
        super.onResume(); WatchService.foregroundOpened();
        if (!WatchService.active() && store.status().startsWith("Vigilancia activa")) store.status("La vigilancia ya no está activa. Comprueba la tienda y vuelve a activarla.");
        update();
    }
    @Override public void onStop() {
        handler.removeCallbacks(refresh); reader.listener = null;
        // Keep the measured WebView in memory for the foreground service. Do not pause its timers.
        if (reader.view.getParent() == browserHost) browserHost.removeView(reader.view);
        super.onStop();
    }
    private void update() {
        if (status == null || isDestroyed()) return;
        status.setText((reader.changingSession() ? "Reiniciando la sesión del navegador…\n" : "")
            + (reader.busy() ? "Leyendo el listado…\n" : "") + (networkBusy ? "Conectando con Telegram…\n" : "")
            + store.status() + "\nBot: " + (store.connected() ? "conectado" : "sin conectar") + " · Pendientes: " + store.pending());
        boolean enabled = !reader.busy() && !reader.changingSession() && !networkBusy && !WatchService.active();
        for (Button button : controls) button.setEnabled(enabled);
        if (browserStatus != null) browserStatus.setText(reader.browserStatus());
        JSONObject scan = store.json("latest"); JSONArray products = scan.optJSONArray("products");
        if (scan.has("checkedAt")) latest.setText(scan.optString("checkedAt") + "\n" + (products == null ? 0 : products.length()) + " productos · " + scan.optInt("pages") + " páginas\n" + scan.optString("reason"));
    }
    private void message(String value) {
        store.status(value == null ? "No se pudo completar la operación." : value); update();
    }
    private void io(Task task) {
        networkBusy = true; update();
        Jobs.NETWORK.execute(() -> {
            String result;
            try { result = task.run(); } catch (Exception e) { result = e.getMessage(); }
            final String answer = result;
            handler.post(() -> { networkBusy = false; message(answer); });
        });
    }
    private void connectionDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView instructions = new TextView(this); instructions.setText("Introduce un token vigente de BotFather. Se guarda cifrado en este teléfono. No lo pegues en chats."); box.addView(instructions);
        EditText token = new EditText(this); token.setHint("Token del bot"); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(token);
        TextView code = new TextView(this); code.setText("Prepara la conexión, abre el bot y pulsa Iniciar. Después confirma aquí."); box.addView(code);
        Button prepare = new Button(this); prepare.setText("Preparar conexión"); box.addView(prepare);
        Button open = new Button(this); open.setText("Abrir mi bot"); box.addView(open);
        Button confirm = new Button(this); confirm.setText("Confirmar conexión"); box.addView(confirm);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Tu bot privado").setView(box).setNegativeButton("Cerrar", null).create();
        prepare.setOnClickListener(v -> {
            String value = token.getText().toString().trim(); token.setText(""); prepare.setEnabled(false); networkBusy = true; update();
            Jobs.NETWORK.execute(() -> {
                String error = null; try { new TelegramClient(store).prepare(value); } catch (Exception e) { error = e.getMessage(); }
                final String failure = error;
                handler.post(() -> {
                    networkBusy = false; prepare.setEnabled(true);
                    if (failure != null) code.setText(failure);
                    else code.setText("Abre el bot y pulsa Iniciar, o envía en privado:\n/start " + store.setupCode());
                    update();
                });
            });
        });
        open.setOnClickListener(v -> { if (store.setupCode().isEmpty()) code.setText("Prepara primero la conexión."); else open(store.botUrl()); });
        confirm.setOnClickListener(v -> {
            confirm.setEnabled(false); networkBusy = true; update();
            Jobs.NETWORK.execute(() -> {
                String error = null; try { new TelegramClient(store).confirm(); } catch (Exception e) { error = e.getMessage(); }
                final String failure = error;
                handler.post(() -> {
                    networkBusy = false; confirm.setEnabled(true);
                    if (failure == null) { dialog.dismiss(); message("Bot conectado. Prueba ahora el mensaje con foto."); }
                    else code.setText(failure);
                    update();
                });
            });
        });
        dialog.show();
    }
    private void photoTest() {
        if (!store.connected()) { message("Conecta primero tu bot privado."); return; }
        reader.scan(false, scan -> {
            if (!scan.optBoolean("complete")) { message(scan.optString("reason")); return; }
            JSONArray products = scan.optJSONArray("products"); JSONObject selected = null;
            for (int i = 0; products != null && i < products.length(); i++) {
                JSONObject p = products.optJSONObject(i);
                if (p != null && !p.isNull("imageUrl") && p.optLong("price") > 0) { selected = p; break; }
            }
            if (selected == null) { message("No hay un producto con foto pública y precio reconocidos para la prueba."); return; }
            final JSONObject product = selected;
            io(() -> { new TelegramClient(store).send(product, true); return "Telegram aceptó la prueba con foto, nombre, precio y enlace. Revisa tu chat privado."; });
        });
    }
    private void activate() {
        if (!store.connected()) { message("Conecta primero tu bot y comprueba el mensaje con foto."); return; }
        JSONObject scan = store.json("latest");
        if (!scan.optBoolean("complete") || !BrowserReader.same(store.scope(), scan.optString("scopeUrl"))) { message("Haz primero una lectura completa de la tienda."); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
            message("Permite las notificaciones y vuelve a pulsar Activar vigilancia."); return;
        }
        try { startForegroundService(new Intent(this, WatchService.class)); }
        catch (RuntimeException e) { message("Android no permitió iniciar la vigilancia. Mantén la app abierta y vuelve a intentarlo."); }
    }
    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { message("No hay una aplicación disponible para abrir el enlace."); }
    }
    private void openStoreInChrome() {
        String url = ListingPolicy.firstPage(store.scope()) ? ListingPolicy.withoutHash(store.scope()) : ListingPolicy.START;
        Intent chrome = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage("com.android.chrome");
        try {
            startActivity(chrome);
            message("Tienda abierta en Chrome. Puedes iniciar sesión allí. Al volver, prueba la lectura de la app: Chrome no le transfiere su sesión.");
        } catch (ActivityNotFoundException e) {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(Intent.createChooser(browser, "Elige un navegador para Mercado Libre"));
                message("No se encontró Chrome. Elige un navegador instalado. Su sesión es independiente de la app.");
            } catch (ActivityNotFoundException missing) { message("Instala o habilita Chrome para abrir Mercado Libre en ese navegador."); }
        }
    }
    private void resetBrowserSession() {
        new AlertDialog.Builder(this).setTitle("Probar una sesión limpia")
            .setMessage("Borrará las cookies, los datos de las páginas y la caché del navegador interno. Mercado Libre puede pedirte iniciar sesión de nuevo. Se conservan el bot, el historial de productos y los avisos pendientes.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Reiniciar sesión", (dialog, which) -> {
                try {
                    reader.resetBrowserSession(() -> message("Sesión del navegador reiniciada. La tienda generará sus cookies normales. Si pide acceso, inicia sesión aquí y luego prueba la lectura."));
                    update();
                } catch (Exception e) { message(e.getMessage()); }
            }).show();
    }
    private void showProducts() {
        JSONArray products = store.json("latest").optJSONArray("products");
        if (products == null || products.length() == 0) { message("Todavía no hay productos leídos."); return; }
        String[] labels = new String[products.length()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject product = products.optJSONObject(i); long price = product.optLong("price");
            labels[i] = product.optString("title") + "\n" + (price > 0 ? "$" + NumberFormat.getIntegerInstance(new Locale("es", "CL")).format(price) + " CLP" : "Precio no visible");
        }
        new AlertDialog.Builder(this).setTitle("Productos de la última lectura").setItems(labels, (dialog, index) -> {
            String url = products.optJSONObject(index).optString("url"); if (ListingPolicy.product(url)) open(url);
        }).setNegativeButton("Cerrar", null).show();
    }
}
