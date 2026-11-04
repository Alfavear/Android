package com.alvear.tvremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

/** Sesión de control (puerto 6466): handshake, pings y envío de teclas. */
final class RemoteSession implements Closeable {

    static final int KEY_HOME = 3, KEY_BACK = 4, KEY_UP = 19, KEY_DOWN = 20, KEY_LEFT = 21,
            KEY_RIGHT = 22, KEY_CENTER = 23, KEY_VOL_UP = 24, KEY_VOL_DOWN = 25, KEY_POWER = 26,
            KEY_MENU = 82, KEY_PLAY_PAUSE = 85, KEY_MUTE = 164, KEY_SETTINGS = 176,
            KEY_DEL = 67, KEY_ENTER = 66;

    private static final int PORT = 6466;
    private static final int FEATURES = 622;
    private static final int DIRECTION_SHORT = 3;

    interface Listener {
        void onClosed(String why);
    }

    private final CertStore store;
    private final Listener listener;
    private SSLSocket sock;
    private InputStream in;
    private OutputStream out;
    private volatile boolean ready;
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    RemoteSession(CertStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    boolean isReady() {
        return ready;
    }

    /** Conecta y espera a que el TV active la sesión. */
    void connect(String host) throws Exception {
        sock = (SSLSocket) store.sslContext().getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress(host, PORT), 5000);
        sock.setSoTimeout(0);
        sock.startHandshake();
        in = sock.getInputStream();
        out = sock.getOutputStream();
        Thread t = new Thread(this::readLoop, "tv-remote-reader");
        t.setDaemon(true);
        t.start();
        if (!readyLatch.await(6, TimeUnit.SECONDS)) {
            close();
            throw new IOException("El TV no respondió (¿está emparejado?)");
        }
    }

    private void readLoop() {
        String why = "Conexión cerrada";
        try {
            while (true) {
                byte[] msgBytes = Proto.readDelimited(in);
                List<Proto.Field> f = Proto.parse(msgBytes);
                if (Proto.get(f, 1) != null) {
                    byte[] device = Proto.cat(Proto.str(1, "Android"), Proto.str(2, "Google"),
                            Proto.num(3, 1), Proto.str(4, "1"),
                            Proto.str(5, "com.alvear.tvremote"), Proto.str(6, "1.0.0"));
                    write(Proto.bytes(1, Proto.cat(Proto.num(1, FEATURES), Proto.bytes(2, device))));
                } else if (Proto.get(f, 2) != null) {
                    write(Proto.bytes(2, Proto.num(1, FEATURES)));
                    ready = true;
                    readyLatch.countDown();
                } else if (Proto.get(f, 8) == null) {
                    if (rawTap != null) rawTap.accept(msgBytes);
                } else if (Proto.get(f, 8) != null) {
                    long val = 0;
                    Proto.Field ping = Proto.get(f, 8);
                    Proto.Field v = Proto.get(Proto.parse(ping.data), 1);
                    if (v != null) val = v.val;
                    write(Proto.bytes(9, Proto.num(1, val)));
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null) why = e.getMessage();
        } finally {
            ready = false;
            close();
            if (listener != null) listener.onClosed(why);
        }
    }

    private void write(byte[] msg) throws IOException {
        synchronized (this) {
            Proto.writeDelimited(out, msg);
        }
    }

    /** Abre un enlace en el TV (RemoteAppLinkLaunchRequest). */
    void sendAppLink(String link) throws IOException {
        if (!ready) throw new IOException("Sin conexión con el TV");
        write(Proto.bytes(90, Proto.str(1, link)));
    }

    /** Depuración: recibe los mensajes crudos del TV que no se procesan. */
    static java.util.function.Consumer<byte[]> rawTap;

    void sendKeyDirection(int keyCode, int direction) throws IOException {
        if (!ready) throw new IOException("Sin conexión con el TV");
        write(Proto.bytes(10, Proto.cat(Proto.num(1, keyCode), Proto.num(2, direction))));
    }

    private static final int SHIFT = 59;

    /** Escribe texto como pulsaciones de teclado: mayúsculas y signos con Shift; sin acentos. */
    void typeText(String text) throws Exception {
        String s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        final String plain = " .,-@/;'=\n";
        final int[] plainK = {62, 56, 55, 69, 77, 76, 74, 75, 70, 66};
        final String shifted = "!?_:*+()\"";
        final int[] shiftedK = {8, 76, 69, 74, 15, 70, 16, 7, 75};
        for (char ch : s.toCharArray()) {
            boolean shift = Character.isUpperCase(ch);
            int k = 0;
            char lc = Character.toLowerCase(ch);
            if (lc >= 'a' && lc <= 'z') k = 29 + (lc - 'a');
            else if (lc >= '0' && lc <= '9') k = 7 + (lc - '0');
            else {
                int pi = plain.indexOf(ch);
                if (pi >= 0) k = plainK[pi];
                else {
                    int si = shifted.indexOf(ch);
                    if (si >= 0) {
                        k = shiftedK[si];
                        shift = true;
                    }
                }
            }
            if (k == 0) continue;
            if (shift) sendKeyDirection(SHIFT, 1);
            sendKeyDirection(k, 3);
            if (shift) sendKeyDirection(SHIFT, 2);
            Thread.sleep(40);
        }
    }

    void sendKey(int keyCode) throws IOException {
        if (!ready) throw new IOException("Sin conexión con el TV");
        write(Proto.bytes(10, Proto.cat(Proto.num(1, keyCode), Proto.num(2, DIRECTION_SHORT))));
    }

    @Override
    public void close() {
        ready = false;
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
    }
}
