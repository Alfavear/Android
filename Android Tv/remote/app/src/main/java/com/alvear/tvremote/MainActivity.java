package com.alvear.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int BG = 0xFF0B0F1E;
    private static final int BTN = 0xFF1C2340;
    private static final int ACCENT = 0xFF3D5AFE;
    private static final int DANGER = 0xFFC62828;

    /** Atajos a apps: nombre, paquete. Se abren con el enlace psl://open del launcher del TV. */
    private static final String[][] SHORTCUTS = {
            {"Netflix", "com.netflix.ninja"},
            {"YouTube", "com.google.android.youtube.tv"},
            {"Disney+", "com.disney.disneyplus"},
            {"Max", "com.wbd.stream"},
            {"Prime", "com.amazon.amazonvideo.livingroom"},
            {"Apple TV", "com.apple.atve.androidtv.appletv"},
            {"Paramount+", "com.cbs.ca"},
            {"Launcher", "com.alvear.psl"},
    };

    private static class Dev {
        String name = "";
        String ip = "";
        String bt = "";
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private CertStore store;
    private RemoteSession session;
    private Pairing pairing;
    private int dp1;

    private final List<Dev> devices = new ArrayList<>();
    private int cur;

    private LinearLayout pairPanel;
    private ScrollView remotePanel;
    private TextView statusView;
    private TextView deviceChip;
    private TextView pairStatus;
    private EditText ipField;
    private EditText codeField;
    private TextView codeButton;
    private TextView confirmButton;
    private TextView backButton;
    private LinearLayout deviceList;
    private LinearLayout dpadBox;
    private FrameLayout padBox;
    private Discovery discovery;
    private final java.util.Set<String> shownDevices = new java.util.HashSet<>();
    private String pendingName = "";
    private String pendingBt = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp1 = Math.round(getResources().getDisplayMetrics().density);
        prefs = getSharedPreferences("tv", Context.MODE_PRIVATE);
        loadDevices();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        buildPairPanel();
        buildRemotePanel();
        root.addView(pairPanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(remotePanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        discovery = new Discovery(this, this::onDeviceFound);
        io.execute(() -> {
            try {
                store = CertStore.loadOrCreate(getFilesDir());
            } catch (Exception e) {
                toast("Error de certificado: " + e.getMessage());
            }
            ui.post(this::showCorrectPanel);
        });
    }

    private int dp(int v) {
        return v * dp1;
    }

    // ---------- Dispositivos guardados ----------

    private void loadDevices() {
        devices.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("devices", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Dev d = new Dev();
                d.name = o.optString("name");
                d.ip = o.optString("ip");
                d.bt = o.optString("bt");
                devices.add(d);
            }
        } catch (Exception ignored) {
        }
        // Migración desde la versión de un solo TV.
        if (devices.isEmpty() && prefs.getBoolean("paired", false)) {
            Dev d = new Dev();
            d.ip = prefs.getString("ip", "192.168.1.5");
            d.name = prefs.getString("tvName", "");
            d.bt = prefs.getString("tvBt", "");
            devices.add(d);
            saveDevices();
        }
        cur = Math.max(0, Math.min(prefs.getInt("cur", 0), Math.max(0, devices.size() - 1)));
    }

    private void saveDevices() {
        JSONArray a = new JSONArray();
        try {
            for (Dev d : devices) {
                a.put(new JSONObject().put("name", d.name).put("ip", d.ip).put("bt", d.bt));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("devices", a.toString()).putInt("cur", cur).apply();
    }

    private Dev current() {
        return devices.isEmpty() ? null : devices.get(cur);
    }

    private String devLabel(Dev d, int i) {
        return d.name.isEmpty() ? "TV " + (i + 1) : d.name;
    }

    private void showCorrectPanel() {
        boolean has = !devices.isEmpty();
        pairPanel.setVisibility(has ? View.GONE : View.VISIBLE);
        remotePanel.setVisibility(has ? View.VISIBLE : View.GONE);
        refreshChip();
        if (has) connectAsync();
    }

    private void refreshChip() {
        Dev d = current();
        deviceChip.setText(d == null ? "Sin TV" : "▾ " + devLabel(d, cur));
    }

    private void showDeviceMenu() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            items.add((i == cur ? "● " : "○ ") + devLabel(devices.get(i), i));
        }
        items.add("＋ Agregar TV nuevo");
        if (!devices.isEmpty()) items.add("🗑 Olvidar «" + devLabel(devices.get(cur), cur) + "»");
        final int n = devices.size();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mis TVs")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < n) {
                        cur = which;
                        saveDevices();
                        refreshChip();
                        io.execute(() -> {
                            if (session != null) session.close();
                            session = null;
                            ensureConnected();
                        });
                    } else if (which == n) {
                        openPairPanel();
                    } else {
                        devices.remove(cur);
                        cur = 0;
                        saveDevices();
                        if (session != null) session.close();
                        session = null;
                        showCorrectPanel();
                    }
                })
                .show();
    }

    private void openPairPanel() {
        pairPanel.setVisibility(View.VISIBLE);
        remotePanel.setVisibility(View.GONE);
        codeField.setVisibility(View.GONE);
        confirmButton.setVisibility(View.GONE);
        backButton.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
        pairStatus.setText("");
        pendingName = "";
        pendingBt = "";
    }

    // ---------- Emparejamiento ----------

    private void buildPairPanel() {
        pairPanel = new LinearLayout(this);
        pairPanel.setOrientation(LinearLayout.VERTICAL);
        pairPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        pairPanel.setPadding(dp(28), dp(72), dp(28), dp(28));

        TextView title = text("Emparejar con un TV", 26, true);
        pairPanel.addView(title, lp(-1, -2, 0, 0, 0, 8));
        TextView hint = text("Conecta el teléfono a la misma red Wi-Fi que el TV. "
                + "Toca el TV de la lista (o escribe su IP) y pulsa el botón; el TV mostrará un código.", 15, false);
        hint.setTextColor(0xFFA0A8C8);
        pairPanel.addView(hint, lp(-1, -2, 0, 0, 0, 24));

        TextView found = text("TVs encontrados en tu red", 14, true);
        found.setTextColor(0xFFA0A8C8);
        pairPanel.addView(found, lp(-1, -2, 0, 0, 0, 8));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        pairPanel.addView(deviceList, lp(-1, -2, 0, 0, 0, 16));

        ipField = new EditText(this);
        ipField.setText(devices.isEmpty() ? "192.168.1.5" : "");
        ipField.setHint("IP del TV");
        style(ipField);
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        pairPanel.addView(ipField, lp(-1, dp(56), 0, 0, 0, 16));

        codeButton = button("Mostrar código en el TV", ACCENT);
        codeButton.setOnClickListener(v -> startPairing());
        pairPanel.addView(codeButton, lp(-1, dp(56), 0, 0, 0, 24));

        codeField = new EditText(this);
        codeField.setHint("Código (6 caracteres)");
        style(codeField);
        codeField.setGravity(Gravity.CENTER);
        codeField.setTextSize(24);
        codeField.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        codeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codeField.setVisibility(View.GONE);
        pairPanel.addView(codeField, lp(-1, dp(64), 0, 0, 0, 16));

        confirmButton = button("Confirmar código", ACCENT);
        confirmButton.setVisibility(View.GONE);
        confirmButton.setOnClickListener(v -> confirmPairing());
        pairPanel.addView(confirmButton, lp(-1, dp(56), 0, 0, 0, 16));

        backButton = button("Volver al mando", BTN);
        backButton.setVisibility(View.GONE);
        backButton.setOnClickListener(v -> {
            pairPanel.setVisibility(View.GONE);
            remotePanel.setVisibility(View.VISIBLE);
        });
        pairPanel.addView(backButton, lp(-1, dp(52), 0, 0, 0, 16));

        pairStatus = text("", 14, false);
        pairStatus.setTextColor(0xFFA0A8C8);
        pairStatus.setGravity(Gravity.CENTER);
        pairPanel.addView(pairStatus, lp(-1, -2, 0, 0, 0, 0));
    }

    private void startPairing() {
        final String ip = ipField.getText().toString().trim();
        if (ip.isEmpty()) {
            pairStatus.setText("Escribe la IP del TV o toca uno de la lista");
            return;
        }
        pairStatus.setText("Conectando con el TV…");
        codeButton.setEnabled(false);
        io.execute(() -> {
            try {
                if (pairing != null) pairing.close();
                pairing = new Pairing(store);
                pairing.start(ip);
                ui.post(() -> {
                    pairStatus.setText("Escribe el código que aparece en la pantalla del TV");
                    codeField.setVisibility(View.VISIBLE);
                    confirmButton.setVisibility(View.VISIBLE);
                    codeField.requestFocus();
                    codeButton.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pairStatus.setText("No se pudo conectar: " + e.getMessage());
                    codeButton.setEnabled(true);
                });
            }
        });
    }

    private void confirmPairing() {
        final String code = codeField.getText().toString();
        final String ip = ipField.getText().toString().trim();
        pairStatus.setText("Verificando…");
        io.execute(() -> {
            try {
                pairing.finish(code);
                pairing.close();
                pairing = null;
                // Alta (o actualización si ya estaba) del TV recién emparejado.
                int idx = -1;
                for (int i = 0; i < devices.size(); i++) if (devices.get(i).ip.equals(ip)) idx = i;
                Dev d = idx >= 0 ? devices.get(idx) : new Dev();
                d.ip = ip;
                if (!pendingName.isEmpty()) d.name = pendingName;
                if (!pendingBt.isEmpty()) d.bt = pendingBt;
                if (idx < 0) {
                    devices.add(d);
                    idx = devices.size() - 1;
                }
                cur = idx;
                saveDevices();
                if (session != null) session.close();
                session = null;
                ui.post(this::showCorrectPanel);
            } catch (Exception e) {
                ui.post(() -> pairStatus.setText("Código incorrecto o sesión caducada: " + e.getMessage()));
            }
        });
    }

    // ---------- Mando ----------

    private void buildRemotePanel() {
        remotePanel = new ScrollView(this);
        remotePanel.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(40), dp(20), dp(20));
        remotePanel.addView(col, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        statusView = text("Desconectado", 15, false);
        statusView.setTextColor(0xFFA0A8C8);
        statusView.setOnClickListener(v -> connectAsync());
        top.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1f));
        deviceChip = button("Sin TV", BTN);
        deviceChip.setTextSize(15);
        deviceChip.setPadding(dp(14), 0, dp(14), 0);
        deviceChip.setOnClickListener(v -> showDeviceMenu());
        top.addView(deviceChip, new LinearLayout.LayoutParams(-2, dp(44)));
        TextView power = key("⏻", RemoteSession.KEY_POWER, false, DANGER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(44), dp(44));
        plp.leftMargin = dp(8);
        top.addView(power, plp);
        col.addView(top, lp(-1, -2, 0, 0, 0, 12));

        // Atajos a apps (2 filas de 4)
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 4; c++) {
                final String[] s = SHORTCUTS[r * 4 + c];
                TextView t = button(s[0], BTN);
                t.setTextSize(12);
                t.setSingleLine(true);
                t.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    openApp(s[1]);
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
                p.leftMargin = dp(3);
                p.rightMargin = dp(3);
                row.addView(t, p);
            }
            col.addView(row, lp(-1, -2, 0, 0, 0, 6));
        }

        // Cambiar entre cruceta y pad táctil
        final TextView mode = button("Pad táctil", BTN);
        mode.setTextSize(14);
        col.addView(mode, lp(-1, dp(40), 0, 8, 0, 10));

        dpadBox = new LinearLayout(this);
        dpadBox.setOrientation(LinearLayout.VERTICAL);
        dpadBox.addView(row(null, key("▲", RemoteSession.KEY_UP, true, BTN), null), lp(-1, dp(80), 0, 0, 0, 10));
        dpadBox.addView(row(key("◀", RemoteSession.KEY_LEFT, true, BTN),
                key("OK", RemoteSession.KEY_CENTER, false, ACCENT),
                key("▶", RemoteSession.KEY_RIGHT, true, BTN)), lp(-1, dp(80), 0, 0, 0, 10));
        dpadBox.addView(row(null, key("▼", RemoteSession.KEY_DOWN, true, BTN), null), lp(-1, dp(80), 0, 0, 0, 0));
        col.addView(dpadBox, lp(-1, -2, 0, 0, 0, 18));

        padBox = new FrameLayout(this);
        TextView padHint = text("Desliza para mover\nToca para OK", 15, false);
        padHint.setTextColor(0xFF6B7396);
        padHint.setGravity(Gravity.CENTER);
        padBox.addView(padHint, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xFF141A33);
        pg.setCornerRadius(dp(22));
        padBox.setBackground(pg);
        padBox.setOnTouchListener(padTouch());
        padBox.setVisibility(View.GONE);
        col.addView(padBox, lp(-1, dp(266), 0, 0, 0, 18));

        mode.setOnClickListener(v -> {
            boolean pad = padBox.getVisibility() != View.VISIBLE;
            padBox.setVisibility(pad ? View.VISIBLE : View.GONE);
            dpadBox.setVisibility(pad ? View.GONE : View.VISIBLE);
            mode.setText(pad ? "Cruceta" : "Pad táctil");
        });

        // Navegación
        col.addView(row(key("↩ Atrás", RemoteSession.KEY_BACK, false, BTN),
                key("⌂ Inicio", RemoteSession.KEY_HOME, false, BTN),
                key("☰ Menú", RemoteSession.KEY_MENU, false, BTN)), lp(-1, dp(60), 0, 0, 0, 10));

        // Reproducción y volumen
        col.addView(row(key("⏯", RemoteSession.KEY_PLAY_PAUSE, false, BTN),
                key("🔇", RemoteSession.KEY_MUTE, false, BTN),
                key("⚙ Ajustes", RemoteSession.KEY_SETTINGS, false, BTN)), lp(-1, dp(60), 0, 0, 0, 10));
        col.addView(row(key("Vol −", RemoteSession.KEY_VOL_DOWN, true, BTN),
                key("Vol +", RemoteSession.KEY_VOL_UP, true, BTN), null), lp(-1, dp(60), 0, 0, 0, 20));

        // Teclado
        LinearLayout kb = new LinearLayout(this);
        kb.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = new EditText(this);
        input.setHint("Escribir en el TV");
        style(input);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        kb.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView send = button("Enviar", ACCENT);
        send.setOnClickListener(v -> {
            typeText(input.getText().toString());
            input.setText("");
        });
        input.setOnEditorActionListener((v, a, e) -> {
            send.performClick();
            return true;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(80), dp(52));
        slp.leftMargin = dp(8);
        kb.addView(send, slp);
        TextView enter = key("↵", RemoteSession.KEY_ENTER, false, BTN);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(52));
        elp.leftMargin = dp(6);
        kb.addView(enter, elp);
        TextView del = button("⌫", BTN);
        del.setOnTouchListener(repeatTouch(RemoteSession.KEY_DEL, true));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(48), dp(52));
        dlp.leftMargin = dp(6);
        kb.addView(del, dlp);
        col.addView(kb, lp(-1, -2, 0, 0, 0, 4));
        TextView note = text("Mayúsculas y símbolos con Shift; los acentos se envían sin tilde.", 11, false);
        note.setTextColor(0xFF6B7396);
        col.addView(note, lp(-1, -2, 0, 0, 0, 0));
    }

    private LinearLayout row(View a, View b, View c) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        View[] vs = {a, b, c};
        for (View v : vs) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
            p.leftMargin = dp(5);
            p.rightMargin = dp(5);
            if (v == null) v = new View(this);
            r.addView(v, p);
        }
        return r;
    }

    private TextView key(String label, int code, boolean repeat, int color) {
        TextView t = button(label, color);
        t.setOnTouchListener(repeatTouch(code, repeat));
        return t;
    }

    private View.OnTouchListener repeatTouch(final int code, final boolean repeat) {
        final Runnable[] rep = new Runnable[1];
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    press(code);
                    if (repeat) {
                        rep[0] = new Runnable() {
                            @Override
                            public void run() {
                                press(code);
                                ui.postDelayed(this, 110);
                            }
                        };
                        ui.postDelayed(rep[0], 450);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    if (rep[0] != null) ui.removeCallbacks(rep[0]);
                    return true;
                default:
                    return true;
            }
        };
    }

    /** Pad táctil: cada 36 dp de desplazamiento envía una flecha; un toque sin mover envía OK. */
    private View.OnTouchListener padTouch() {
        final float[] st = new float[5]; // lastX, lastY, accX, accY, total
        return (v, e) -> {
            float step = dp(36);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getX();
                    st[1] = e.getY();
                    st[2] = st[3] = st[4] = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - st[0], dy = e.getY() - st[1];
                    st[0] = e.getX();
                    st[1] = e.getY();
                    st[2] += dx;
                    st[3] += dy;
                    st[4] += Math.abs(dx) + Math.abs(dy);
                    if (Math.abs(st[2]) >= step && Math.abs(st[2]) >= Math.abs(st[3])) {
                        press(st[2] > 0 ? RemoteSession.KEY_RIGHT : RemoteSession.KEY_LEFT);
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        st[2] = st[3] = 0;
                    } else if (Math.abs(st[3]) >= step) {
                        press(st[3] > 0 ? RemoteSession.KEY_DOWN : RemoteSession.KEY_UP);
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        st[2] = st[3] = 0;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (st[4] < dp(10)) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        press(RemoteSession.KEY_CENTER);
                    }
                    return true;
                default:
                    return true;
            }
        };
    }

    // ---------- Conexión ----------

    private void connectAsync() {
        io.execute(this::ensureConnected);
    }

    /** Solo en el hilo io. */
    private boolean ensureConnected() {
        if (session != null && session.isReady()) return true;
        Dev d = current();
        if (d == null || store == null) return false;
        setStatus("Conectando…");
        if (tryConnect(d.ip)) return true;
        // La IP pudo cambiar: buscar el TV por su nombre en la red.
        if (!d.name.isEmpty() || !d.bt.isEmpty()) {
            setStatus("Buscando el TV en la red…");
            String found = Discovery.find(this, d.name, d.bt, 6000);
            if (found != null && !found.equals(d.ip)) {
                d.ip = found;
                saveDevices();
                if (tryConnect(found)) return true;
            }
        }
        setStatus("Desconectado (toca para reintentar)");
        return false;
    }

    private boolean tryConnect(String ip) {
        try {
            if (session != null) session.close();
            session = new RemoteSession(store, why -> setStatus("Desconectado"));
            session.connect(ip);
            setStatus("● Conectado");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Muestra los TVs vistos en la red y aprende el nombre de los ya emparejados. */
    private void onDeviceFound(final String name, final String host, final String bt) {
        boolean changed = false;
        for (Dev d : devices) {
            if (d.ip.equals(host) && (d.name.isEmpty() || !d.name.equals(name))) {
                d.name = name;
                if (!bt.isEmpty()) d.bt = bt;
                changed = true;
            }
        }
        if (changed) {
            saveDevices();
            ui.post(this::refreshChip);
        }
        ui.post(() -> {
            if (!shownDevices.add(name + host)) return;
            TextView b = button(name + "  ·  " + host, BTN);
            b.setTextSize(15);
            b.setOnClickListener(v -> {
                ipField.setText(host);
                pendingName = name;
                pendingBt = bt;
                pairStatus.setText("Seleccionado: " + name);
            });
            deviceList.addView(b, lp(-1, dp(48), 0, 0, 0, 8));
        });
    }

    private void press(final int code) {
        io.execute(() -> {
            try {
                if (!ensureConnected()) return;
                session.sendKey(code);
            } catch (Exception e) {
                setStatus("Desconectado (toca para reintentar)");
            }
        });
    }

    private void openApp(final String pkg) {
        io.execute(() -> {
            try {
                if (!ensureConnected()) return;
                session.sendAppLink("psl://open?pkg=" + pkg);
            } catch (Exception e) {
                setStatus("Desconectado (toca para reintentar)");
            }
        });
    }

    private void typeText(final String s) {
        io.execute(() -> {
            try {
                if (!ensureConnected()) return;
                session.typeText(s);
            } catch (Exception e) {
                setStatus("Desconectado (toca para reintentar)");
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(() -> {
            if (statusView != null) {
                statusView.setText(s);
                statusView.setTextColor(s.startsWith("●") ? 0xFF66BB6A : 0xFFA0A8C8);
            }
        });
    }

    private void toast(final String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    /** Con la app a la vista: búsqueda de TVs y conexión. Al salir se sueltan para no gastar batería. */
    @Override
    protected void onStart() {
        super.onStart();
        if (discovery != null) discovery.start();
        if (store != null && !devices.isEmpty()) connectAsync();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (discovery != null) discovery.stop();
        io.execute(() -> {
            if (session != null) session.close();
            session = null;
        });
        setStatus("Desconectado");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discovery != null) discovery.stop();
        if (session != null) session.close();
        if (pairing != null) pairing.close();
        io.shutdownNow();
    }

    // ---------- Utilidades de vista ----------

    private TextView text(String s, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }

    private void style(EditText e) {
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF6B7396);
        e.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF141A33);
        g.setCornerRadius(dp(14));
        e.setBackground(g);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(bottom));
        return p;
    }
}
