package cl.pokeofertas;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import android.net.Uri;
import org.json.*;
import java.text.NumberFormat;
import java.util.*;
import androidx.work.*;

public class MainActivity extends Activity {
    LinearLayout body;
    static String money(long v) { return "$" + NumberFormat.getIntegerInstance(new Locale("es","CL")).format(v); }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
        SyncWorker.schedule(this); render();
    }
    @Override public void onResume() { super.onResume(); render(); }
    TextView text(LinearLayout parent, String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(24,41,55));
        t.setPadding(0,8,0,8); parent.addView(t); return t;
    }
    void button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); parent.addView(b); b.setOnClickListener(v->action.run());
    }
    void render() {
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,24,24,40);
        body.setBackgroundColor(Color.rgb(242,247,248)); scroll.addView(body); setContentView(scroll);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v,insets)-> {
            if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom); }
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        text(body,"PokéOfertas",30);
        text(body,"CHILE · VENDEDOR 550072427",13);
        String server = Store.prefs(this).getString("server","");
        text(body,server.isEmpty() ? "Modo manual · vigilancia automática sin configurar" : "Vigilancia configurada · consultas cada 15 min aprox.",15);
        text(body,Store.prefs(this).getString("status","Todavía no hay una consulta automática confirmada."),13);
        button(body,"Abrir productos del vendedor",()->open(Store.SELLER_URL));
        button(body,"Agregar producto",()->edit(null));
        button(body,"Configurar vigilancia",this::settings);
        if(!server.isEmpty()) button(body,"Consultar ahora",()-> {
            WorkManager.getInstance(this).enqueueUniqueWork("manual-sync",ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(SyncWorker.class).build());
            toast("Consulta solicitada. Usa Actualizar pantalla para ver el resultado.");
        });
        button(body,"Actualizar pantalla",this::render);
        JSONArray products=Store.items(this);
        if(products.length()==0) text(body,"Agrega una publicación real para empezar. No hay precios de ejemplo ni ofertas inventadas.",17);
        for(int i=0;i<products.length();i++) {
            JSONObject p=products.optJSONObject(i); if(p==null) continue;
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(20,20,20,20); card.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.setMargins(0,20,0,0); body.addView(card,params);
            text(card,p.optString("title"),20);
            text(card,money(p.optLong("price")) + (p.optBoolean("shippingKnown") ? " + envío " + money(p.optLong("shipping")) : " · envío sin confirmar"),23);
            if(!p.optBoolean("active",true)) text(card,"Publicación no activa",15);
            if(p.optLong("target")>0) text(card,"Objetivo total: " + money(p.optLong("target")) + (Store.deal(p) ? (p.optBoolean("shippingKnown") ? " · OBJETIVO ALCANZADO" : " · precio base bajo objetivo; confirmar envío") : ""),15);
            if(p.optLong("reference")>0 && p.optBoolean("shippingKnown")) {
                long total=p.optLong("price")+p.optLong("shipping");
                double percent=100.0*(p.optLong("reference")-total)/p.optLong("reference");
                text(card,"Referencia manual con envío: " + money(p.optLong("reference")) + " · diferencia " + String.format(Locale.US,"%.1f%%",percent),14);
            }
            text(card,"Fuente: " + p.optString("source","manual") + " · " + java.text.DateFormat.getDateTimeInstance().format(new Date(p.optLong("checked"))),12);
            if(System.currentTimeMillis()-p.optLong("checked")>3_600_000) text(card,"Dato de hace más de una hora: confirma el precio antes de publicar.",13);
            button(card,"Abrir en Mercado Libre",()->open(p.optString("url")));
            button(card,"Copiar mensaje para Facebook",()->copy(p));
            button(card,"Editar precio / objetivo / afiliado",()->edit(p));
            button(card,"Ver historial",()->history(p));
            button(card,"Eliminar de mi lista",()->new AlertDialog.Builder(this).setMessage("¿Eliminar este producto? Si sigue en el vendedor, la sincronización puede volver a agregarlo.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(d,w)->remove(p.optString("id"))).show());
        }
    }
    void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    void open(String raw) {
        if(!Store.productUrl(raw)) { toast("Se necesita un enlace HTTPS de Mercado Libre Chile."); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(raw))); } catch(Exception e) { toast("No hay navegador disponible."); }
    }
    EditText field(LinearLayout parent,String label,String value,boolean numeric) {
        text(parent,label,14); EditText e=new EditText(this); e.setText(value); e.setSingleLine();
        e.setInputType(numeric ? android.text.InputType.TYPE_CLASS_NUMBER : android.text.InputType.TYPE_CLASS_TEXT);
        parent.addView(e); return e;
    }
    long amount(EditText e, boolean required) {
        String raw=e.getText().toString().trim();
        if(raw.isEmpty()&&!required) return 0;
        if(!raw.matches("[0-9]{1,10}")) throw new IllegalArgumentException("Ingresa pesos enteros sin puntos ni símbolos.");
        long n=Long.parseLong(raw); if(required&&n<=0) throw new IllegalArgumentException("El precio debe ser mayor que cero."); return n;
    }
    void edit(JSONObject original) {
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(24,10,24,10);
        JSONObject initial=original==null?new JSONObject():original;
        EditText title=field(form,"Nombre · incluye edición, idioma y formato",initial.optString("title"),false);
        EditText url=field(form,"Enlace original del producto",initial.optString("url"),false);
        EditText price=field(form,"Precio CLP",initial.has("price")?initial.optString("price"):"",true);
        EditText shipping=field(form,"Envío CLP · vacío si no lo sabes",initial.optBoolean("shippingKnown")?initial.optString("shipping","0"):"",true);
        EditText target=field(form,"Precio objetivo total CLP · opcional",initial.optString("target",""),true);
        EditText ref=field(form,"Referencia equivalente CON envío CLP · opcional",initial.optString("reference",""),true);
        EditText affiliate=field(form,"Tu enlace de afiliado · opcional",initial.optString("affiliate"),false);
        ScrollView scroll=new ScrollView(this); scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(original==null?"Agregar producto":"Actualizar producto")
            .setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String name=title.getText().toString().trim(), link=url.getText().toString().trim(), aff=affiliate.getText().toString().trim();
                if(name.isEmpty()||!Store.productUrl(link)) throw new IllegalArgumentException("Ingresa nombre y enlace HTTPS de Mercado Libre Chile.");
                if(!aff.isEmpty()&&!Store.productUrl(aff)) throw new IllegalArgumentException("El enlace de afiliado debe ser HTTPS de Mercado Libre Chile.");
                long value=amount(price,true),ship=amount(shipping,false),goal=amount(target,false),reference=amount(ref,false);
                synchronized(Store.class) {
                    JSONArray all=Store.items(this);
                    JSONObject p=new JSONObject(initial.toString());
                    if(original==null) {
                        java.util.regex.Matcher match=java.util.regex.Pattern.compile("MLC-?([0-9]+)").matcher(link);
                        String id=match.find()?"MLC"+match.group(1):UUID.randomUUID().toString();
                        for(int k=0;k<all.length();k++) if(id.equals(all.getJSONObject(k).optString("id"))) throw new IllegalArgumentException("Este producto ya está en tu lista.");
                        p.put("id",id);
                    }
                    p.put("title",name).put("url",link).put("target",goal).put("shipping",ship).put("reference",reference)
                        .put("shippingKnown",!shipping.getText().toString().trim().isEmpty()).put("affiliate",aff).put("source","manual").put("active",true);
                    Store.sample(p,value);
                    boolean replaced=false;
                    for(int k=0;k<all.length();k++) if(p.getString("id").equals(all.getJSONObject(k).optString("id"))) { all.put(k,p); replaced=true; break; }
                    if(!replaced) all.put(p); Store.save(this,all);
                }
                dialog.dismiss(); render();
            } catch(Exception e) { toast(e.getMessage()); }
        })); dialog.show();
    }
    void copy(JSONObject p) {
        if(!p.optBoolean("active",true)) { toast("Confirma que la publicación esté activa antes de compartir."); return; }
        String aff=p.optString("affiliate");
        String content="Pokémon TCG: " + p.optString("title") + "\nPrecio consultado: " + money(p.optLong("price"))
            + (p.optBoolean("shippingKnown")?"\nEnvío consultado: "+money(p.optLong("shipping")):"\nEnvío: confirmar según destino.")
            + "\nConsulta: " + java.text.DateFormat.getDateTimeInstance().format(new Date(p.optLong("checked")))
            + "\nPrecio y disponibilidad pueden cambiar.\n" + (aff.isEmpty()?p.optString("url"):aff)
            + (aff.isEmpty()?"\nEnlace original: reemplazar por tu enlace de afiliado antes de publicar.":"\nEnlace de afiliado: puedo recibir una comisión por compras válidas.");
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Oferta Pokémon",content));
        toast(aff.isEmpty()?"Copiado. Falta reemplazar el enlace por el de afiliado.":"Mensaje copiado.");
    }
    void history(JSONObject p) {
        StringBuilder s=new StringBuilder(); JSONArray h=p.optJSONArray("history");
        if(h!=null) for(int i=h.length()-1;i>=0;i--) { JSONObject entry=h.optJSONObject(i); if(entry!=null) s.append(java.text.DateFormat.getDateTimeInstance().format(new Date(entry.optLong("at")))).append(" · ").append(money(entry.optLong("price"))).append("\n"); }
        new AlertDialog.Builder(this).setTitle("Historial de precio del producto").setMessage(s.length()==0?"Sin historial":s.toString()).setPositiveButton("Cerrar",null).show();
    }
    void remove(String id) {
        synchronized(Store.class) { JSONArray all=Store.items(this); for(int i=all.length()-1;i>=0;i--) if(id.equals(all.optJSONObject(i).optString("id"))) all.remove(i); Store.save(this,all); }
        render();
    }
    void settings() {
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(24,10,24,10);
        text(form,"Opcional. Sin servidor los precios se registran manualmente. El acceso al vendedor por API debe comprobarse.",14);
        EditText endpoint=field(form,"URL del servidor HTTPS",Store.prefs(this).getString("server",""),false);
        EditText key=field(form,"Clave del servidor",Store.prefs(this).getString("key",""),false);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox fresh=new CheckBox(this); fresh.setText("Avisar de productos nuevos"); fresh.setChecked(Store.prefs(this).getBoolean("new",false)); form.addView(fresh);
        CheckBox drops=new CheckBox(this); drops.setText("Avisar de bajadas de precio"); drops.setChecked(Store.prefs(this).getBoolean("drops",true)); form.addView(drops);
        text(form,"La primera carga puede incluir productos antiguos. Se consideran nuevos para esta app. Android puede retrasar las consultas.",13);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Vigilancia").setView(form).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=endpoint.getText().toString().trim();
            try {
                if(!value.isEmpty()) { java.net.URI u=new java.net.URI(value); if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null) throw new Exception(); }
                if(!value.isEmpty()&&key.getText().toString().trim().isEmpty()) { toast("Ingresa la clave del servidor."); return; }
            } catch(Exception e) { toast("Usa una URL HTTPS válida, sin credenciales ni parámetros."); return; }
            Store.prefs(this).edit().putString("server",value).putString("key",key.getText().toString().trim()).putBoolean("new",fresh.isChecked()).putBoolean("drops",drops.isChecked()).apply();
            SyncWorker.schedule(this); d.dismiss(); render();
        })); d.show();
    }
}
