package com.alvear.psl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cargador de imágenes con caché en memoria y en disco. Toda la decodificación y el procesado
 * (desvanecido del póster) se hacen en segundo plano, una sola vez por imagen.
 */
final class Images {

    interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(14 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static File dir;

    private Images() {
    }

    static void init(Context c) {
        dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
    }

    static void load(String url, int maxDim, Callback cb) {
        load(url, maxDim, false, cb);
    }

    /** fadeLeft=true devuelve la imagen con el borde izquierdo desvanecido a transparente. */
    static void load(final String url, final int maxDim, final boolean fadeLeft, final Callback cb) {
        final String key = url + "@" + maxDim + (fadeLeft ? "f" : "");
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            cb.onLoaded(hit);
            return;
        }
        POOL.execute(() -> {
            Bitmap b = fetch(url, maxDim);
            if (b != null && fadeLeft) b = fade(b);
            final Bitmap out = b;
            if (out != null) MEM.put(key, out);
            MAIN.post(() -> {
                if (out != null) cb.onLoaded(out);
            });
        });
    }

    /** Imagen con esquinas redondeadas ya aplicadas (evita recortar en cada fotograma). */
    static void loadRounded(final String url, final int maxDim, final int radius, final Callback cb) {
        final String key = url + "@" + maxDim + "r" + radius;
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            cb.onLoaded(hit);
            return;
        }
        POOL.execute(() -> {
            Bitmap b = fetch(url, maxDim);
            if (b != null) {
                Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                c.drawRoundRect(0, 0, b.getWidth(), b.getHeight(), radius, radius, p);
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
                c.drawBitmap(b, 0, 0, p);
                b = out;
            }
            final Bitmap res = b;
            if (res != null) MEM.put(key, res);
            MAIN.post(() -> {
                if (res != null) cb.onLoaded(res);
            });
        });
    }

    static void clear() {
        MEM.evictAll();
    }

    /** Descarga a disco sin mantener nada en memoria (decodifica en miniatura). */
    static void prefetch(final String url) {
        POOL.execute(() -> fetch(url, 64));
    }

    private static Bitmap fade(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, 0, 0, null);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setShader(new LinearGradient(0, 0, src.getWidth() * 0.55f, 0,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, src.getWidth(), src.getHeight(), p);
        return out;
    }

    private static Bitmap fetch(String url, int maxDim) {
        return fetch(url, maxDim, false);
    }

    private static Bitmap fetch(String url, int maxDim, boolean alpha) {
        try {
            File f = new File(dir, Integer.toHexString(url.hashCode()));
            if (!f.exists() || f.length() == 0) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "PSLauncher/1.0");
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (FileInputStream in = new FileInputStream(f)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            if (!alpha) o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (FileInputStream in = new FileInputStream(f)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
