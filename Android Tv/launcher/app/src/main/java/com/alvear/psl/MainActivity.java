package com.alvear.psl;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String[] PRIORITY = {
            "netflix", "youtube", "disney", "max", "prime", "apple", "paramount", "mercado"
    };
    /** Espera tras detenerse el foco antes de cargar contenido pesado (evita trabajo al recorrer rápido). */
    private static final long APP_DELAY = 130;
    private static final long CARD_DELAY = 80;
    private static final long PREFETCH_DELAY = 1200;

    /** Apps que no se muestran en el launcher (no se desinstalan). */
    private static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.mercadolibre.android.mplay_tv",
            "com.yodo1.crossyroad",
            "com.doodle.turboracing3d"));
    private static final int MAX_RECENT = 5;

    private static class AppItem {
        String label;
        String pkg;
        Intent intent;
        Drawable icon;
        int color;
        View view;
        long lastUsed;
        boolean recent;
        boolean special;
    }

    /**
     * Dos capas fijas que se funden entre sí. Evita redibujar degradados o reescalar imágenes en cada
     * fotograma: solo cambia el alpha de capas ya compuestas.
     */
    private static class Cross extends FrameLayout {
        private final ImageView a, b;
        private ImageView front;

        Cross(android.content.Context c, ImageView.ScaleType type) {
            super(c);
            a = new ImageView(c);
            b = new ImageView(c);
            for (ImageView v : new ImageView[]{a, b}) {
                v.setScaleType(type);
                v.setAlpha(0f);
                addView(v, new FrameLayout.LayoutParams(-1, -1));
            }
            front = a;
        }

        void show(Bitmap bmp, Drawable d, long ms, float fromScale) {
            final ImageView next = front == a ? b : a;
            final ImageView prev = front;
            if (bmp != null) next.setImageBitmap(bmp);
            else next.setImageDrawable(d);
            next.animate().cancel();
            next.setAlpha(0f);
            next.setScaleX(fromScale);
            next.setScaleY(fromScale);
            next.bringToFront();
            front = next;
            next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(() -> {
                if (prev != front) prev.setAlpha(0f);
            }).start();
        }

        void hide(long ms) {
            // Se apagan las dos capas: con cambios rápidos la anterior puede seguir visible.
            for (ImageView v : new ImageView[]{a, b}) {
                v.animate().cancel();
                v.animate().alpha(0f).setDuration(ms).start();
            }
        }
    }

    /** ScrollView horizontal cuyo desplazamiento controlamos nosotros (animado y suave). */
    private static class SmoothScroll extends HorizontalScrollView {
        SmoothScroll(android.content.Context c) {
            super(c);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppItem> apps = new ArrayList<>();
    private String loadedKey = "";
    private final Map<String, Drawable> iconCache = new java.util.HashMap<>();
    private final Map<String, Integer> colorCache = new java.util.HashMap<>();
    private static final int SELECTOR_DP = 112;
    private FrameLayout iconHolder;
    private View selector;
    private ObjectAnimator pulse;
    private boolean selectorShown;
    private boolean depthOnCards;
    private int recentCount;
    private int normalCount;
    private List<AppItem> baseList = new ArrayList<>();
    private String focusPkg;
    private int dp1;

    private AppItem selected;
    private int detailToken;
    private int cardsToken;
    private int bannerToken = -1;
    private boolean bannerShown;
    private int currentColor = 0;
    private Runnable pending;
    private Runnable prefetch;

    private Cross bg, banner;
    private ImageView heroView, posterImg;
    private SmoothScroll iconScroll, cardScroll;
    private LinearLayout iconRow, cardRow;
    private TextView clockView, dateView, tagView, titleView, metaView, summaryView;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            String d = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(now);
            dateView.setText(d.substring(0, 1).toUpperCase(new Locale("es")) + d.substring(1));
            handler.postDelayed(this, 10000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp1 = Math.round(getResources().getDisplayMetrics().density);
        Images.init(this);
        buildUi();
        getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener((o, n) -> onFocusChanged(o, n));
        ContentRepo.load(this, () -> handler.post(() -> {
            if (selected != null) applySelection(selected);
        }));
    }

    private int dp(int v) {
        return v * dp1;
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        bg = new Cross(this, ImageView.ScaleType.FIT_XY);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        recolor(0xFF0A1030);

        banner = new Cross(this, ImageView.ScaleType.CENTER_CROP);
        root.addView(banner, new FrameLayout.LayoutParams(-1, -1));

        // Póster en grande (con el borde ya desvanecido) para las series sin fondo 16:9.
        posterImg = new ImageView(this);
        posterImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterImg.setAlpha(0f);
        root.addView(posterImg, new FrameLayout.LayoutParams(dp(560), -1, Gravity.END));

        // Un solo degradado combinado, cacheado como textura.
        View scrim = new View(this);
        scrim.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xE6000000, 0x99000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x99000000, 0x00000000, 0xD9000000})}));
        scrim.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(48), dp(14), dp(48), dp(10));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout top = new FrameLayout(this);
        dateView = label(15, 0xCCFFFFFF, false);
        top.addView(dateView, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        clockView = label(22, Color.WHITE, false);
        top.addView(clockView, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        TextView attr = label(10, 0x88FFFFFF, false);
        attr.setText("This product uses the TMDB API but is not endorsed or certified by TMDB.");
        top.addView(attr, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        content.addView(top, new LinearLayout.LayoutParams(-1, dp(32)));

        iconScroll = new SmoothScroll(this);
        setupScroll(iconScroll, dp(24), dp(18));
        iconHolder = new FrameLayout(this);
        iconHolder.setClipChildren(false);
        iconRow = new LinearLayout(this);
        iconRow.setClipChildren(false);
        iconHolder.addView(iconRow, new FrameLayout.LayoutParams(-2, -2));
        // Un único selector que se desliza hasta el icono enfocado (en vez de un aro por icono).
        selector = new View(this);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Color.TRANSPARENT);
        sg.setCornerRadius(dp(26));
        sg.setStroke(dp(3), Color.WHITE);
        selector.setBackground(sg);
        selector.setAlpha(0f);
        iconHolder.addView(selector, new FrameLayout.LayoutParams(dp(SELECTOR_DP), dp(SELECTOR_DP)));
        pulse = ObjectAnimator.ofPropertyValuesHolder(selector,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.045f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.045f));
        pulse.setDuration(900);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        iconScroll.addView(iconHolder, new FrameLayout.LayoutParams(-2, -2));
        content.addView(iconScroll, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout info = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        tagView = label(14, 0xFFB8C4FF, true);
        tagView.setLetterSpacing(0.12f);
        col.addView(tagView, new LinearLayout.LayoutParams(-2, -2));
        titleView = label(36, Color.WHITE, true);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(titleView, lpm(-2, -2, 0, 4, 0, 0));
        metaView = label(15, 0xFFE0E4FF, false);
        col.addView(metaView, lpm(-2, -2, 0, 6, 0, 0));
        summaryView = label(16, 0xE6FFFFFF, false);
        summaryView.setMaxLines(4);
        summaryView.setEllipsize(TextUtils.TruncateAt.END);
        summaryView.setLineSpacing(0, 1.15f);
        col.addView(summaryView, lpm(-2, -2, 0, 10, 0, 0));
        info.addView(col, new FrameLayout.LayoutParams(dp(520), -1, Gravity.START));

        heroView = new ImageView(this);
        heroView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroView.setBackgroundColor(0xFF1B1F33);
        heroView.setAlpha(0f);
        rounded(heroView, 16);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(150), dp(150),
                Gravity.END | Gravity.CENTER_VERTICAL);
        hlp.rightMargin = dp(60);
        info.addView(heroView, hlp);
        content.addView(info, new LinearLayout.LayoutParams(-1, 0, 1f));

        cardScroll = new SmoothScroll(this);
        setupScroll(cardScroll, dp(16), dp(10));
        cardRow = new LinearLayout(this);
        cardRow.setClipChildren(false);
        cardScroll.addView(cardRow, new FrameLayout.LayoutParams(-2, -2));
        content.addView(cardScroll, new LinearLayout.LayoutParams(-1, dp(144)));

        setContentView(root);
    }

    private void setupScroll(SmoothScroll s, int padH, int padV) {
        s.setHorizontalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setPadding(padH, padV, padH, padV);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private TextView label(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lpm(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void rounded(View v, final int radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) Images.clear();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (iconRow.getChildCount() > 0) iconRow.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof ContentRepo.Show) focusSelectedIcon();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            int k = e.getKeyCode();
            if (f != null && f.getTag() instanceof ContentRepo.Show) {
                if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    focusSelectedIcon();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            } else if (f != null && f.getTag() instanceof AppItem) {
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    flush();
                    if (cardRow.getChildCount() > 0) cardRow.getChildAt(0).requestFocus();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void focusSelectedIcon() {
        if (selected != null && selected.view != null) selected.view.requestFocus();
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppItem> byPkg = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || hiddenSet().contains(pkg) || byPkg.containsKey(pkg)) continue;
                AppItem it = new AppItem();
                it.pkg = pkg;
                it.label = String.valueOf(ri.loadLabel(pm));
                it.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                byPkg.put(pkg, it);
            }
        }
        List<AppItem> list = new ArrayList<>(byPkg.values());
        final List<String> order = new ArrayList<>();
        for (String p : appPrefs().getString("order", "").split(",")) if (!p.isEmpty()) order.add(p);
        Collections.sort(list, (a, b) -> {
            int ia = order.indexOf(a.pkg), ib = order.indexOf(b.pkg);
            if (ia >= 0 && ib >= 0) return ia - ib;
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
            int pa = priority(a), pb = priority(b);
            if (pa != pb) return pa - pb;
            return a.label.compareToIgnoreCase(b.label);
        });
        baseList = list;

        // Recientes primero: así, al encender, la última app usada es la primera de la fila.
        Map<String, Long> last = recentTimes();
        List<AppItem> recents = new ArrayList<>();
        for (AppItem a : list) {
            Long t = last.get(a.pkg);
            if (t != null) {
                a.lastUsed = t;
                recents.add(a);
            }
        }
        Collections.sort(recents, (x, y) -> Long.compare(y.lastUsed, x.lastUsed));
        if (recents.size() > MAX_RECENT) recents = new ArrayList<>(recents.subList(0, MAX_RECENT));
        List<AppItem> ordered = new ArrayList<>(recents);
        for (AppItem a : list) if (!recents.contains(a)) ordered.add(a);
        for (AppItem a : ordered) a.recent = recents.contains(a);

        List<AppItem> specials = buildSpecials();
        StringBuilder key = new StringBuilder().append(recents.size()).append(':');
        for (AppItem a : ordered) key.append(a.pkg).append(',');
        key.append('|').append(specials.size());
        if (key.toString().equals(loadedKey)) return;
        loadedKey = key.toString();

        for (AppItem it : ordered) {
            Drawable cached = iconCache.get(it.pkg);
            if (cached == null) {
                try {
                    cached = pm.getActivityIcon(it.intent);
                } catch (PackageManager.NameNotFoundException e) {
                    cached = pm.getDefaultActivityIcon();
                }
                iconCache.put(it.pkg, cached);
                colorCache.put(it.pkg, dominantColor(toBitmap(cached, 48)));
            }
            it.icon = cached;
            it.color = colorCache.get(it.pkg);
        }
        recentCount = recents.size();
        normalCount = ordered.size();
        apps.clear();
        apps.addAll(ordered);
        apps.addAll(specials);
        populateIcons();
    }

    // ---- Apps ocultas, orden y ajustes rápidos ----

    private android.content.SharedPreferences appPrefs() {
        return getSharedPreferences("apps", MODE_PRIVATE);
    }

    private java.util.Set<String> hiddenSet() {
        android.content.SharedPreferences p = appPrefs();
        if (!p.contains("hidden")) p.edit().putStringSet("hidden", new java.util.HashSet<>(HIDDEN)).apply();
        return new java.util.HashSet<>(p.getStringSet("hidden", new java.util.HashSet<String>()));
    }

    private List<AppItem> buildSpecials() {
        List<AppItem> out = new ArrayList<>();
        String[][] defs = {
                {"Wi-Fi", "android.settings.WIFI_SETTINGS"},
                {"Bluetooth", "android.settings.BLUETOOTH_SETTINGS"},
                {"Pantalla", "android.settings.DISPLAY_SETTINGS"},
        };
        PackageManager pm = getPackageManager();
        for (String[] d : defs) {
            Intent in = new Intent(d[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (in.resolveActivity(pm) == null) continue;
            out.add(special(d[0], in));
        }
        if (!hiddenSet().isEmpty()) out.add(special("Ocultas", null));
        return out;
    }

    private AppItem special(String label, Intent intent) {
        AppItem it = new AppItem();
        it.special = true;
        it.pkg = "special:" + label;
        it.label = label;
        it.intent = intent;
        it.icon = tileIcon(label);
        it.color = 0xFF1E2A55;
        return it;
    }

    private Drawable tileIcon(String text) {
        int size = 160;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2A3050);
        c.drawRoundRect(0, 0, size, size, 36, 36, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(text.length() > 7 ? 26 : 32);
        c.drawText(text, size / 2f, size / 2f + 11, p);
        return new BitmapDrawable(getResources(), b);
    }

    private void showAppMenu(final AppItem it) {
        if (it.special) {
            if (it.intent == null) showHiddenDialog();
            return;
        }
        List<String> opts = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (!it.recent) {
            opts.add("◀  Mover a la izquierda");
            ids.add(0);
            opts.add("▶  Mover a la derecha");
            ids.add(1);
        }
        opts.add("Ocultar app");
        ids.add(2);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.label)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    int id = ids.get(which);
                    if (id == 2) hideApp(it);
                    else moveApp(it, id == 0 ? -1 : 1);
                })
                .show();
    }

    private void moveApp(AppItem it, int dir) {
        int vi = apps.indexOf(it);
        int ni = vi + dir;
        if (ni < 0 || ni >= normalCount) return;
        AppItem other = apps.get(ni);
        if (other.recent || other.special) return;
        int a = baseList.indexOf(it), b = baseList.indexOf(other);
        if (a < 0 || b < 0) return;
        Collections.swap(baseList, a, b);
        StringBuilder sb = new StringBuilder();
        for (AppItem x : baseList) sb.append(x.pkg).append(',');
        appPrefs().edit().putString("order", sb.toString()).apply();
        focusPkg = it.pkg;
        loadedKey = "";
        loadApps();
    }

    private void hideApp(AppItem it) {
        java.util.Set<String> h = hiddenSet();
        h.add(it.pkg);
        appPrefs().edit().putStringSet("hidden", h).apply();
        loadedKey = "";
        loadApps();
    }

    private void showHiddenDialog() {
        final List<String> pkgs = new ArrayList<>(hiddenSet());
        Collections.sort(pkgs);
        if (pkgs.isEmpty()) return;
        PackageManager pm = getPackageManager();
        String[] names = new String[pkgs.size()];
        for (int i = 0; i < names.length; i++) {
            try {
                names[i] = "Mostrar " + pm.getApplicationLabel(pm.getApplicationInfo(pkgs.get(i), 0));
            } catch (PackageManager.NameNotFoundException e) {
                names[i] = "Mostrar " + pkgs.get(i);
            }
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apps ocultas")
                .setItems(names, (d, which) -> {
                    java.util.Set<String> h = hiddenSet();
                    h.remove(pkgs.get(which));
                    appPrefs().edit().putStringSet("hidden", h).apply();
                    loadedKey = "";
                    loadApps();
                })
                .show();
    }

    /** Última vez que se usó cada app: estadísticas de uso del sistema (si hay permiso) y lo abierto desde aquí. */
    private Map<String, Long> recentTimes() {
        Map<String, Long> m = new java.util.HashMap<>();
        android.content.SharedPreferences sp = getSharedPreferences("recent", MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getValue() instanceof Long) m.put(e.getKey(), (Long) e.getValue());
        }
        try {
            android.app.usage.UsageStatsManager um =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = um.queryEvents(now - 14L * 86400000L, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    Long old = m.get(e.getPackageName());
                    if (old == null || e.getTimeStamp() > old) m.put(e.getPackageName(), e.getTimeStamp());
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private int priority(AppItem a) {
        String l = a.label.toLowerCase(Locale.ROOT);
        for (int i = 0; i < PRIORITY.length; i++) {
            if (l.contains(PRIORITY[i])) return i;
        }
        return PRIORITY.length;
    }

    private void populateIcons() {
        iconRow.removeAllViews();
        selectorShown = false;
        selector.animate().cancel();
        selector.setAlpha(0f);
        pulse.cancel();
        selected = null;
        final int size = dp(80);
        for (int idx = 0; idx < apps.size(); idx++) {
            final AppItem it = apps.get(idx);
            ImageView iv = new ImageView(this);
            // Los iconos se redondean una sola vez en un bitmap, no con recorte en cada fotograma.
            iv.setImageBitmap(roundedBitmap(it.icon, 160, 36));
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setFocusable(true);
            iv.setTag(it);
            it.view = iv;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = dp(26);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(v -> launch(it));
            iv.setLongClickable(true);
            iv.setOnLongClickListener(v -> {
                showAppMenu(it);
                return true;
            });
            iconRow.addView(iv);
            // Separador entre las apps recientes y el resto, y antes de los ajustes rápidos.
            boolean afterRecents = recentCount > 0 && idx == recentCount - 1 && recentCount < normalCount;
            boolean beforeSpecials = idx == normalCount - 1 && normalCount < apps.size();
            if (afterRecents || beforeSpecials) {
                View sep = new View(this);
                sep.setBackgroundColor(0x55FFFFFF);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(2), dp(56));
                slp.gravity = Gravity.CENTER_VERTICAL;
                slp.rightMargin = dp(26);
                iconRow.addView(sep, slp);
            }
        }
        if (iconRow.getChildCount() > 0) {
            View target = iconRow.getChildAt(0);
            if (focusPkg != null) {
                for (AppItem a : apps) if (a.pkg.equals(focusPkg) && a.view != null) target = a.view;
                focusPkg = null;
            }
            final View first = target;
            iconRow.post(() -> {
                first.requestFocus();
                if (selected == null) {
                    selected = (AppItem) first.getTag();
                    tagView.setText(tagText(selected));
                    applySelection(selected);
                    styleView(first);
                }
            });
        }
    }

    private void launch(AppItem it) {
        if (it.special) {
            if (it.intent == null) showHiddenDialog();
            else {
                try {
                    startActivity(it.intent);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        getSharedPreferences("recent", MODE_PRIVATE).edit().putLong(it.pkg, System.currentTimeMillis()).apply();
        try {
            startActivity(it.intent, ActivityOptions
                    .makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle());
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- foco y estilo

    private void schedule(long delay, final Runnable r) {
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            r.run();
        };
        handler.postDelayed(pending, delay);
    }

    private void flush() {
        if (pending == null) return;
        Runnable p = pending;
        handler.removeCallbacks(p);
        pending = null;
        p.run();
    }

    private void onFocusChanged(View oldF, View newF) {
        if (newF == null) return;
        Object tag = newF.getTag();
        AppItem prev = selected;
        if (tag instanceof AppItem) {
            final AppItem it = (AppItem) tag;
            ensureVisible(iconScroll, newF);
            moveSelector(newF);
            setDepth(false);
            if (it != selected) {
                selected = it;
                tagView.setText(tagText(it));
                schedule(APP_DELAY, () -> applySelection(it));
            }
        } else if (tag instanceof ContentRepo.Show) {
            final ContentRepo.Show s = (ContentRepo.Show) tag;
            ensureVisible(cardScroll, newF);
            hideSelector();
            setDepth(true);
            schedule(CARD_DELAY, () -> showDetail(s));
        }
        if (oldF != null) styleView(oldF);
        styleView(newF);
        if (prev != null && prev.view != null) styleView(prev.view);
        if (selected != null && selected.view != null) styleView(selected.view);
    }

    private void styleView(View v) {
        Object tag = v.getTag();
        boolean focused = v.isFocused();
        if (tag instanceof AppItem) {
            boolean sel = tag == selected;
            float s = focused ? 1.32f : sel ? 1.14f : 1f;
            v.animate().scaleX(s).scaleY(s).alpha(focused || sel ? 1f : 0.82f)
                    .setDuration(220)
                    .setInterpolator(focused ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                    .start();
        } else if (tag instanceof ContentRepo.Show) {
            float s = focused ? 1.14f : 1f;
            v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(200)
                    .setInterpolator(focused ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator())
                    .start();
            ((ImageView) v).setForeground(focused ? ring(12) : null);
        }
    }

    private void moveSelector(final View v) {
        if (v.getWidth() == 0) {
            v.post(() -> moveSelector(v));
            return;
        }
        float x = v.getLeft() + v.getWidth() / 2f - dp(SELECTOR_DP) / 2f;
        float y = v.getTop() + v.getHeight() / 2f - dp(SELECTOR_DP) / 2f;
        selector.animate().cancel();
        if (!selectorShown) {
            selectorShown = true;
            selector.setTranslationX(x);
            selector.setTranslationY(y);
            selector.setAlpha(0f);
            selector.animate().alpha(1f).setDuration(180).start();
            if (!pulse.isStarted()) pulse.start();
        } else {
            selector.animate().translationX(x).translationY(y).alpha(1f).setDuration(300)
                    .setInterpolator(new OvershootInterpolator(0.9f)).start();
        }
    }

    private void hideSelector() {
        if (!selectorShown) return;
        selectorShown = false;
        selector.animate().cancel();
        selector.animate().alpha(0f).setDuration(150).start();
        pulse.cancel();
        selector.setScaleX(1f);
        selector.setScaleY(1f);
    }

    /** Al bajar a los pósters, la fila de apps se aleja un poco: da sensación de profundidad. */
    private void setDepth(boolean cards) {
        if (cards == depthOnCards) return;
        depthOnCards = cards;
        iconScroll.setPivotX(0f);
        iconScroll.setPivotY(iconScroll.getHeight() / 2f);
        iconScroll.animate().scaleX(cards ? 0.88f : 1f).scaleY(cards ? 0.88f : 1f)
                .translationY(cards ? -dp(6) : 0).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private Drawable ring(int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    private void ensureVisible(SmoothScroll s, View v) {
        int sx = s.getScrollX();
        int viewport = s.getWidth() - s.getPaddingLeft() - s.getPaddingRight();
        int margin = dp(70);
        int target = sx;
        if (v.getLeft() - sx < margin) target = v.getLeft() - margin;
        else if (v.getRight() - sx > viewport - margin) target = v.getRight() - viewport + margin;
        int max = Math.max(0, s.getChildAt(0).getWidth() - viewport);
        target = Math.max(0, Math.min(max, target));
        if (target == sx) return;
        Object prev = s.getTag();
        if (prev instanceof ObjectAnimator) ((ObjectAnimator) prev).cancel();
        ObjectAnimator a = ObjectAnimator.ofInt(s, "scrollX", sx, target);
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        s.setTag(a);
        a.start();
    }

    // ---------------------------------------------------------------- contenido

    private String tagText(AppItem it) {
        String name = (it.recent ? "RECIENTE  ·  " : "") + it.label.toUpperCase(Locale.getDefault());
        return ContentRepo.forApp(it.label).isEmpty() ? name : name + "  ·  TENDENCIAS";
    }

    private void applySelection(final AppItem it) {
        if (it != selected) return;
        List<ContentRepo.Show> shows = ContentRepo.forApp(it.label);
        buildCards(shows);
        tagView.setText(tagText(it));
        if (shows.isEmpty()) showAppInfo(it);
        else showDetail(shows.get(0));
        if (prefetch != null) handler.removeCallbacks(prefetch);
        final List<ContentRepo.Show> list = shows;
        prefetch = () -> prefetchBackgrounds(list);
        handler.postDelayed(prefetch, PREFETCH_DELAY);
    }

    /** Precarga los fondos de la plataforma en la que el usuario se detuvo, con baja prioridad. */
    private void prefetchBackgrounds(List<ContentRepo.Show> shows) {
        for (ContentRepo.Show s : shows) {
            String known = ContentRepo.bgUrlCached(s);
            if (known == null) {
                ContentRepo.fetchBackground(this, s, true, url -> {
                    if (!url.isEmpty()) Images.prefetch(url);
                });
            } else if (!known.isEmpty()) {
                Images.prefetch(known);
            }
        }
    }

    private void buildCards(List<ContentRepo.Show> shows) {
        final int token = ++cardsToken;
        cardRow.removeAllViews();
        cardScroll.scrollTo(0, 0);
        int w = dp(80), h = dp(116);
        for (int i = 0; i < shows.size(); i++) {
            final ContentRepo.Show s = shows.get(i);
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setFocusable(true);
            iv.setTag(s);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = dp(18);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(v -> {
                if (selected != null) launch(selected);
            });
            iv.setAlpha(0f);
            iv.setTranslationX(dp(40));
            cardRow.addView(iv);
            iv.animate().alpha(1f).translationX(0).setStartDelay(30L * i).setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            Images.loadRounded(s.img, 256, 24, bmp -> {
                if (token == cardsToken) iv.setImageBitmap(bmp);
            });
        }
    }

    private void showDetail(final ContentRepo.Show s) {
        final int token = ++detailToken;
        setText(titleView, s.name, 0);
        StringBuilder meta = new StringBuilder();
        if (!s.ep.isEmpty()) meta.append(s.ep);
        if (!s.genres.isEmpty()) meta.append(meta.length() > 0 ? "   ·   " : "").append(s.genres);
        if (!s.lang.isEmpty()) meta.append(meta.length() > 0 ? "   ·   " : "").append(s.lang);
        if (s.rating > 0) meta.append("   ·   ★ ").append(String.format(Locale.US, "%.1f", s.rating));
        setText(metaView, meta.toString(), 40);
        setText(summaryView, s.summary.isEmpty() ? "Sin sinopsis disponible." : s.summary, 80);
        heroView.animate().alpha(0f).setDuration(180).start();

        String bgUrl = ContentRepo.bgUrlCached(s);
        final boolean knownBanner = bgUrl != null && !bgUrl.isEmpty();
        if (bgUrl == null) {
            ContentRepo.fetchBackground(this, s, false, url -> handler.post(() -> {
                if (token == detailToken) applyBanner(url, token);
            }));
        } else {
            applyBanner(bgUrl, token);
        }
        Images.load(s.img, 600, true, bmp -> {
            if (token != detailToken) return;
            if (!(bannerShown && bannerToken == token)) recolor(dominantColor(small(bmp)));
            if (!knownBanner && bannerToken != token) showPoster(bmp);
        });
    }

    private void showAppInfo(AppItem it) {
        ++detailToken;
        String version = "";
        String updated = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(it.pkg, 0);
            if (pi.versionName != null) version = "Versión " + pi.versionName;
            updated = "Actualizada el " + new SimpleDateFormat("d MMM yyyy", new Locale("es"))
                    .format(new Date(pi.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        setText(titleView, it.label, 0);
        setText(metaView, version + (version.isEmpty() || updated.isEmpty() ? "" : "   ·   ") + updated, 40);
        setText(summaryView, "Pulsa OK para abrir.", 80);
        hideBanner();
        hidePoster();
        heroView.setImageBitmap(toBitmap(it.icon, 256));
        heroView.setAlpha(0f);
        heroView.animate().alpha(1f).setDuration(300).start();
        recolor(it.color);
    }

    private void applyBanner(String url, final int token) {
        if (url == null || url.isEmpty()) {
            hideBanner();
            return;
        }
        Images.load(url, 1024, bmp -> {
            if (token != detailToken) return;
            bannerToken = token;
            bannerShown = true;
            banner.show(bmp, null, 700, 1.07f);
            hidePoster();
            recolor(dominantColor(Bitmap.createScaledBitmap(bmp, 16, 9, true)));
        });
    }

    private void hideBanner() {
        if (!bannerShown) return;
        bannerShown = false;
        banner.hide(350);
    }

    private void showPoster(Bitmap bmp) {
        posterImg.setImageBitmap(bmp);
        posterImg.animate().cancel();
        posterImg.setAlpha(0f);
        posterImg.setTranslationX(dp(30));
        posterImg.animate().alpha(1f).translationX(0).setDuration(400)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    private void hidePoster() {
        posterImg.animate().cancel();
        posterImg.animate().alpha(0f).setDuration(220).start();
    }

    private void setText(TextView t, String text, long delay) {
        t.animate().cancel();
        t.setText(text);
        t.setAlpha(0f);
        t.setTranslationY(dp(12));
        t.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    private void recolor(int color) {
        if (color == currentColor) return;
        currentColor = color;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, darken(color, 0.2f)});
        bg.show(null, g, 500, 1f);
    }

    // ---------------------------------------------------------------- utilidades de imagen

    private Bitmap small(Bitmap b) {
        return Bitmap.createScaledBitmap(b, 16, 24, true);
    }

    private Bitmap roundedBitmap(Drawable d, int size, int radius) {
        Bitmap src = toBitmap(d, size);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        c.drawRoundRect(0, 0, size, size, radius, radius, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private int dominantColor(Bitmap bmp) {
        float[] hsv = new float[3];
        double sumH = 0, sumS = 0, wSum = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                if (Color.alpha(c) < 200) continue;
                Color.colorToHSV(c, hsv);
                double w = hsv[1] * hsv[2] + 0.01;
                sumH += hsv[0] * w;
                sumS += hsv[1] * w;
                wSum += w;
            }
        }
        if (wSum == 0) return 0xFF0A1030;
        float h = (float) (sumH / wSum);
        float s = Math.max(0.5f, Math.min(0.95f, (float) (sumS / wSum) + 0.2f));
        return Color.HSVToColor(new float[]{h, s, 0.5f});
    }

    private Bitmap toBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private int darken(int c, float f) {
        return Color.argb(255,
                (int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }
}
