package com.alvear.rexton;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Launcher para la radio Android del SsangYong Rexton W.
 * Todo se construye por código (sin layouts XML) y escala según la altura de pantalla.
 */
public class MainActivity extends Activity {

    private static final int CYAN = 0xFF2EA8FF;
    private static final int PANEL = 0xE60A1226;
    private static final int OIL_INTERVAL_KM = 5000;

    private float S;                       // escala: alto de pantalla / 1024
    private FrameLayout root;
    private FrameLayout drawer;
    private LinearLayout dock;
    private TextView timeV, ampmV, dateV, oilValue;
    private View oilFill, oilRest;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PackageManager pm;
    private List<AppInfo> apps = new ArrayList<>();

    private static class AppInfo {
        String label, pkg;
        Drawable icon;
    }

    private static class Slot {
        final String key, label, glyph;
        final int color;
        final String[] defaults;
        Slot(String key, String label, String glyph, int color, String... defaults) {
            this.key = key; this.label = label; this.glyph = glyph; this.color = color; this.defaults = defaults;
        }
    }

    private final Slot[] slots = new Slot[]{
            new Slot("menu", "Menú", "▦", 0xFF1E88E5),
            new Slot("maps", "Maps", "⌖", 0xFF2E7D32, "com.google.android.apps.maps"),
            new Slot("audio", "Audio", "♪", 0xFF7B1FA2, "com.spotify.music", "com.google.android.apps.youtube.music"),
            new Slot("video", "Video", "▶", 0xFFD32F2F, "com.google.android.youtube"),
            new Slot("radio", "Radio", "◉", 0xFF1565C0),
            new Slot("waze", "Waze", "☺", 0xFF29B6F6, "com.waze"),
            new Slot("settings", "Configuración", "⚙", 0xFF455A64),
    };

    private int px(float n) { return Math.round(n * S); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        prefs = getSharedPreferences("rexton", MODE_PRIVATE);
        S = getResources().getDisplayMetrics().heightPixels / 1024f;

        root = new FrameLayout(this);
        root.setBackground(background());
        setContentView(root);

        buildPanel();
        buildDock();
        buildTopBar();
        buildDrawer();
        loadApps();
        refreshDock();
        refreshOil();
    }

    // ---------- fondo ----------
    private Drawable background() {
        // Si existe res/drawable-nodpi/bg.jpg (o bg.png) se usa como fondo.
        int id = getResources().getIdentifier("bg", "drawable", getPackageName());
        if (id != 0) {
            ImageView iv = new ImageView(this);
            Drawable d = getResources().getDrawable(id);
            return d;
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B2A55, 0xFF3A3F7A, 0xFFB5604A, 0xFF1A1420});
        return g;
    }

    // ---------- barra superior ----------
    private void buildTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(20), 0, px(20), 0);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2050B1A, 0xD90A1630});
        top.setBackground(g);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(130)));

        TextView home = new TextView(this);
        home.setText("⌂");
        home.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(70));
        home.setTextColor(Color.WHITE);
        home.setGravity(Gravity.CENTER);
        home.setBackground(round(0xFF0B2A5B, px(20), CYAN, px(3)));
        makeClickable(home, new View.OnClickListener() {
            @Override public void onClick(View v) { hideDrawer(); }
        }, null);
        top.addView(home, new LinearLayout.LayoutParams(px(135), px(95)));

        TextView brand = text("SSANGYONG", 38, Color.WHITE, true);
        brand.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = px(24);
        top.addView(brand, bp);

        top.addView(divider(), dividerLp());

        TextView model = text("REXTON W", 46, Color.WHITE, true);
        model.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
        top.addView(model);

        top.addView(divider(), dividerLp());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        timeV = text("--:--", 68, Color.WHITE, false);
        ampmV = text("", 28, Color.WHITE, false);
        dateV = text("", 34, Color.WHITE, false);
        row.addView(timeV);
        row.addView(ampmV);
        View d2 = divider();
        LinearLayout.LayoutParams d2p = dividerLp();
        row.addView(d2, d2p);
        row.addView(dateV);
        col.addView(row);
        TextView welcome = text("Bienvenido SsangYong Rexton W", 36, CYAN, false);
        welcome.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC));
        col.addView(welcome);
        top.addView(col);

        tickClock();
    }

    private void tickClock() {
        Date now = new Date();
        Locale es = new Locale("es", "CO");
        timeV.setText(new SimpleDateFormat("h:mm", es).format(now));
        ampmV.setText(" " + new SimpleDateFormat("a", Locale.US).format(now));
        String d = new SimpleDateFormat("EEE, d MMM yyyy", es).format(now).replace(".", "");
        dateV.setText(d.substring(0, 1).toUpperCase() + d.substring(1));
        handler.postDelayed(new Runnable() { @Override public void run() { tickClock(); } }, 10000);
    }

    // ---------- panel derecho ----------
    private void buildPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(315), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = px(148);
        lp.rightMargin = px(20);
        root.addView(panel, lp);

        // Voltaje (requiere lectura CAN: por ahora sin señal)
        panel.addView(card("⚡", 0xFF2ECC71, "Voltaje batería", "-- V", null, null), cardLp());

        // Cambio de aceite (manual)
        LinearLayout[] oil = new LinearLayout[1];
        View oilCard = card("⛽", CYAN, "Cambio de aceite", "Toca para configurar", oil, null);
        oilCard.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { oilDialog(); } });
        oilCard.setFocusable(true);
        TextView[] tv = findValueView((ViewGroup) oilCard);
        oilValue = tv[0];
        oilFill = oil[0].getChildAt(0);
        oilRest = oil[0].getChildAt(1);
        panel.addView(oilCard, cardLp());

        // Combustible y clima (requieren CAN)
        LinearLayout[] fuel = new LinearLayout[1];
        panel.addView(card("⛽", CYAN, "Combustible", "E   —   F", fuel, null), cardLp());
        panel.addView(card("❄", CYAN, "Clima", "--°C", null, null), cardLp());
    }

    private TextView[] findValueView(ViewGroup card) {
        LinearLayout content = (LinearLayout) card.getChildAt(1);
        return new TextView[]{(TextView) content.getChildAt(1)};
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(112));
        lp.bottomMargin = px(14);
        return lp;
    }

    /** Tarjeta: [icono] [titulo / valor / barra opcional]. barOut recibe la barra si se pide. */
    private View card(String glyph, int glyphColor, String title, String value, LinearLayout[] barOut, Object unused) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(14), px(8), px(14), px(8));
        c.setBackground(round(PANEL, px(24), 0xFF1E5FBF, px(2)));

        TextView ic = text(glyph, 48, glyphColor, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(round(0xFF0B1430, px(18), 0, 0));
        c.addView(ic, new LinearLayout.LayoutParams(px(84), px(84)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(16), 0, 0, 0);
        content.addView(text(title, 27, Color.WHITE, false));
        content.addView(text(value, 40, Color.WHITE, true));
        if (barOut == null) {
            // oil card needs a bar: created by caller via barOut array (non-null)
        } else {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(round(0xFF1B2A44, px(6), 0, 0));
            View fill = new View(this);
            fill.setBackground(round(CYAN, px(6), 0, 0));
            View rest = new View(this);
            bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
            bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f));
            content.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(10)));
            barOut[0] = bar;
        }
        c.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return c;
    }

    private void setBar(float pct) {
        pct = Math.max(0, Math.min(100, pct));
        ((LinearLayout.LayoutParams) oilFill.getLayoutParams()).weight = pct;
        ((LinearLayout.LayoutParams) oilRest.getLayoutParams()).weight = 100 - pct;
        ((View) oilFill.getParent()).requestLayout();
    }

    private void refreshOil() {
        int cur = prefs.getInt("odo", -1);
        int last = prefs.getInt("oil_last", -1);
        if (cur < 0 || last < 0) {
            oilValue.setText("Toca para configurar");
            oilValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(24));
            setBar(0);
            return;
        }
        int left = last + OIL_INTERVAL_KM - cur;
        oilValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(36));
        oilValue.setText(left >= 0 ? String.format(Locale.US, "En: %,d km", left)
                : String.format(Locale.US, "Vencido %,d km", -left));
        oilValue.setTextColor(left >= 0 ? Color.WHITE : 0xFFFF5252);
        setBar(left * 100f / OIL_INTERVAL_KM);
    }

    private void oilDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("Kilometraje actual");
        int cur = prefs.getInt("odo", -1);
        if (cur >= 0) et.setText(String.valueOf(cur));
        new AlertDialog.Builder(this)
                .setTitle("Cambio de aceite (cada " + OIL_INTERVAL_KM + " km)")
                .setView(et)
                .setPositiveButton("Guardar km", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        saveOdo(et, false);
                    }
                })
                .setNeutralButton("Cambio hecho", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        saveOdo(et, true);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void saveOdo(EditText et, boolean serviceDone) {
        try {
            int km = Integer.parseInt(et.getText().toString().trim());
            SharedPreferences.Editor e = prefs.edit().putInt("odo", km);
            if (serviceDone || prefs.getInt("oil_last", -1) < 0) e.putInt("oil_last", km);
            e.apply();
            refreshOil();
        } catch (NumberFormatException ignored) { }
    }

    // ---------- dock inferior ----------
    private void buildDock() {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(px(30), px(14), px(30), px(10));
        dock.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9071022, 0xF2050B1A}));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(190));
        lp.gravity = Gravity.BOTTOM;
        root.addView(dock, lp);
    }

    private void refreshDock() {
        dock.removeAllViews();
        for (final Slot s : slots) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);

            FrameLayout tile = new FrameLayout(this);
            tile.setBackground(round(darken(s.color), px(26), brighten(s.color), px(3)));
            String pkg = resolvePkg(s);
            Drawable icon = null;
            if (pkg != null) {
                try { icon = pm.getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
            }
            if (icon != null && !s.key.equals("menu") && !s.key.equals("settings")) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(icon);
                iv.setPadding(px(8), px(8), px(8), px(8));
                tile.setBackground(round(0x00000000, px(26), 0, 0));
                tile.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                TextView g = text(s.glyph, 64, Color.WHITE, false);
                g.setGravity(Gravity.CENTER);
                tile.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            cell.addView(tile, new LinearLayout.LayoutParams(px(118), px(118)));

            TextView lbl = text(s.label, 28, Color.WHITE, false);
            lbl.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = px(6);
            cell.addView(lbl, llp);

            makeClickable(cell, new View.OnClickListener() {
                @Override public void onClick(View v) { launchSlot(s); }
            }, new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (s.key.equals("menu")) return false;
                    pickApp(s);
                    return true;
                }
            });
            dock.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private String resolvePkg(Slot s) {
        String saved = prefs.getString("slot_" + s.key, null);
        if (saved != null && installed(saved)) return saved;
        for (String d : s.defaults) if (installed(d)) return d;
        return null;
    }

    private boolean installed(String pkg) {
        return pm.getLaunchIntentForPackage(pkg) != null;
    }

    private void launchSlot(Slot s) {
        if (s.key.equals("menu")) { showDrawer(); return; }
        if (s.key.equals("settings") && prefs.getString("slot_settings", null) == null) {
            startSafe(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        String pkg = resolvePkg(s);
        if (pkg == null) { pickApp(s); return; }
        startSafe(pm.getLaunchIntentForPackage(pkg));
    }

    private void startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    private void pickApp(final Slot s) {
        GridView gv = appGrid(5);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Elegir app para " + s.label)
                .setView(gv)
                .setNegativeButton("Cancelar", null)
                .create();
        gv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString("slot_" + s.key, apps.get(pos).pkg).apply();
                dlg.dismiss();
                refreshDock();
            }
        });
        dlg.show();
    }

    // ---------- menú de apps ----------
    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF5050B1A);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showDrawer() {
        drawer.removeAllViews();
        GridView gv = appGrid(7);
        gv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                hideDrawer();
                startSafe(pm.getLaunchIntentForPackage(apps.get(pos).pkg));
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topMargin = px(140);
        drawer.addView(gv, lp);
        drawer.setVisibility(View.VISIBLE);
        drawer.bringToFront();
        // la barra superior queda por encima para poder volver con ⌂
        root.getChildAt(2).bringToFront();
    }

    private void hideDrawer() { drawer.setVisibility(View.GONE); }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) hideDrawer();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideDrawer();
    }

    private void loadApps() {
        apps.clear();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            AppInfo a = new AppInfo();
            a.pkg = ri.activityInfo.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            apps.add(a);
        }
        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo x, AppInfo y) { return x.label.compareToIgnoreCase(y.label); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        refreshDock();
    }

    private GridView appGrid(int cols) {
        GridView gv = new GridView(this);
        gv.setNumColumns(cols);
        gv.setPadding(px(30), px(10), px(30), px(30));
        gv.setVerticalSpacing(px(20));
        gv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout l = new LinearLayout(MainActivity.this);
                l.setOrientation(LinearLayout.VERTICAL);
                l.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(MainActivity.this);
                iv.setImageDrawable(apps.get(p).icon);
                l.addView(iv, new LinearLayout.LayoutParams(px(96), px(96)));
                TextView t = text(apps.get(p).label, 24, Color.WHITE, false);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(1);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                l.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return l;
            }
        });
        return gv;
    }

    // ---------- utilidades de UI ----------
    private TextView text(String s, float sizePx, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(sizePx));
        t.setTextColor(color);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int fill, int radius, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x802EA8FF);
        return v;
    }

    private LinearLayout.LayoutParams dividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(2), px(70));
        lp.leftMargin = px(24);
        lp.rightMargin = px(24);
        return lp;
    }

    private int darken(int c) {
        return Color.rgb((int) (Color.red(c) * 0.45f), (int) (Color.green(c) * 0.45f), (int) (Color.blue(c) * 0.45f));
    }

    private int brighten(int c) {
        return Color.rgb(Math.min(255, (int) (Color.red(c) * 1.3f)), Math.min(255, (int) (Color.green(c) * 1.3f)), Math.min(255, (int) (Color.blue(c) * 1.3f)));
    }

    /** Clic, clic largo y realce de foco (para botones del volante / perilla). */
    private void makeClickable(View v, View.OnClickListener c, View.OnLongClickListener lc) {
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(c);
        if (lc != null) v.setOnLongClickListener(lc);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean f) {
                view.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(120).start();
            }
        });
    }
}
