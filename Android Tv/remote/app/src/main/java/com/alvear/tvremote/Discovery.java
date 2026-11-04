package com.alvear.tvremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Búsqueda de TVs Android/Google TV en la red por mDNS (_androidtvremote2._tcp). */
final class Discovery {

    private static final String TAG = "TvDiscovery";
    private static final String TYPE = "_androidtvremote2._tcp.";

    interface Callback {
        void onFound(String name, String host, String bt);
    }

    private final NsdManager nsd;
    private final Callback callback;
    private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener listener;

    Discovery(Context ctx, Callback callback) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        this.callback = callback;
    }

    void start() {
        listener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) {
                Log.w(TAG, "start failed " + e);
            }

            public void onStopDiscoveryFailed(String t, int e) {
            }

            public void onDiscoveryStarted(String t) {
            }

            public void onDiscoveryStopped(String t) {
            }

            public void onServiceFound(NsdServiceInfo info) {
                synchronized (pending) {
                    pending.add(info);
                }
                resolveNext();
            }

            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    void stop() {
        try {
            if (listener != null) nsd.stopServiceDiscovery(listener);
        } catch (Exception ignored) {
        }
        listener = null;
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (pending) {
            if (resolving) return;
            next = pending.poll();
            if (next == null) return;
            resolving = true;
        }
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo i, int e) {
                done();
            }

            public void onServiceResolved(NsdServiceInfo i) {
                String bt = "";
                Map<String, byte[]> attrs = i.getAttributes();
                if (attrs != null && attrs.get("bt") != null) {
                    bt = new String(attrs.get("bt"), StandardCharsets.UTF_8);
                }
                if (i.getHost() != null) {
                    Log.i(TAG, "found " + i.getServiceName() + " " + i.getHost() + " bt=" + bt);
                    callback.onFound(i.getServiceName(), i.getHost().getHostAddress(), bt);
                }
                done();
            }

            private void done() {
                synchronized (pending) {
                    resolving = false;
                }
                resolveNext();
            }
        });
    }

    /** Bloquea hasta encontrar el TV con ese nombre (o bt) o agotar el tiempo. Devuelve su IP o null. */
    static String find(Context ctx, final String name, final String bt, long timeoutMs) {
        final String[] result = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Discovery d = new Discovery(ctx, (n, host, b) -> {
            boolean byBt = bt != null && !bt.isEmpty() && bt.equalsIgnoreCase(b);
            boolean byName = name != null && !name.isEmpty() && name.equals(n);
            if (byBt || byName) {
                result[0] = host;
                latch.countDown();
            }
        });
        d.start();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        d.stop();
        return result[0];
    }
}
