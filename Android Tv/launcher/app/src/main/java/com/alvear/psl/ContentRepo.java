package com.alvear.psl;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Series y películas recientes por plataforma de streaming, desde la API de TMDB (región Colombia, en español). */
final class ContentRepo {

    private static final String KEY = "TU_API_KEY_TMDB";
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final String POSTER = "https://image.tmdb.org/t/p/w780";
    private static final String BACKDROP = "https://image.tmdb.org/t/p/w1280";
    private static final String REGION = "CO";
    private static final String LANG = "es-MX";
    private static final long MAX_AGE_MS = 12L * 3600 * 1000;
    private static final int PER_KIND = 8;

    static final class Show {
        int id;
        String name = "";
        String img = "";
        String bg = "";
        String summary = "";
        String genres = "";
        String lang = "";
        String ep = "";
        double rating;
    }

    /** Palabra clave en el nombre de la app -> id del proveedor en TMDB (región CO). */
    private static final String[][] PROVIDERS = {
            {"netflix", "8"},
            {"hbo", "1899"},
            {"max", "1899"},
            {"prime", "119"},
            {"disney", "337"},
            {"apple", "350"},
            {"paramount", "531"},
            {"mercado", "2302"},
            {"claro", "167"},
            {"crunchyroll", "283"},
    };
    private static final String[] PROVIDER_IDS = {"8", "1899", "119", "337", "350", "531", "2302", "167", "283"};

    private static volatile Map<String, List<Show>> data = new HashMap<>();

    private ContentRepo() {
    }

    static List<Show> forApp(String label) {
        String l = label.toLowerCase(Locale.ROOT).trim();
        for (String[] p : PROVIDERS) {
            boolean match = p[0].equals("max") ? (l.equals("max") || l.startsWith("max ")) : l.contains(p[0]);
            if (!match) continue;
            List<Show> s = data.get(p[1]);
            return s == null ? Collections.<Show>emptyList() : s;
        }
        return Collections.emptyList();
    }

    // ---- Los fondos ya vienen en los datos de TMDB; estas funciones mantienen la interfaz con la UI ----

    interface UrlCallback {
        void onUrl(String url);
    }

    static String bgUrlCached(Show s) {
        return s.bg;
    }

    static void fetchBackground(Context ctx, Show s, boolean low, UrlCallback cb) {
        cb.onUrl(s.bg);
    }

    /** Carga primero la caché (rápida) y luego refresca de la red si hace falta. onChange se llama en un hilo secundario. */
    static void load(final Context ctx, final Runnable onChange) {
        new Thread(() -> {
            File cf = new File(ctx.getFilesDir(), "content3.json");
            boolean fresh = cf.exists() && System.currentTimeMillis() - cf.lastModified() < MAX_AGE_MS;
            if (cf.exists()) {
                try {
                    data = parseCache(new String(readAll(cf), "UTF-8"));
                    onChange.run();
                } catch (Exception ignored) {
                }
            }
            if (fresh) return;
            try {
                Map<String, List<Show>> fetched = fetchAll();
                if (fetched.isEmpty()) return;
                data = fetched;
                try (FileOutputStream o = new FileOutputStream(cf)) {
                    o.write(toCache(fetched).getBytes("UTF-8"));
                }
                onChange.run();
            } catch (Exception ignored) {
            }
        }, "content-load").start();
    }

    private static Map<String, List<Show>> fetchAll() throws Exception {
        final Map<Integer, String> tvGenres = genres("tv");
        final Map<Integer, String> movieGenres = genres("movie");
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(System.currentTimeMillis() - 365L * 86400000L));

        ExecutorService ex = Executors.newFixedThreadPool(4);
        Map<String, Future<List<Show>>> tv = new HashMap<>();
        Map<String, Future<List<Show>>> mv = new HashMap<>();
        for (final String id : PROVIDER_IDS) {
            final String tvUrl = discoverUrl("tv", id, "first_air_date.gte=" + since);
            final String mvUrl = discoverUrl("movie", id, "primary_release_date.gte=" + since);
            tv.put(id, ex.submit(new Callable<List<Show>>() {
                @Override
                public List<Show> call() {
                    return fetchList(tvUrl, false, tvGenres);
                }
            }));
            mv.put(id, ex.submit(new Callable<List<Show>>() {
                @Override
                public List<Show> call() {
                    return fetchList(mvUrl, true, movieGenres);
                }
            }));
        }
        Map<String, List<Show>> res = new HashMap<>();
        for (String id : PROVIDER_IDS) {
            List<Show> a = tv.get(id).get();
            List<Show> b = mv.get(id).get();
            List<Show> mix = new ArrayList<>();
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                if (i < a.size()) mix.add(a.get(i));
                if (i < b.size()) mix.add(b.get(i));
            }
            if (!mix.isEmpty()) res.put(id, mix);
        }
        ex.shutdown();
        return res;
    }

    private static String discoverUrl(String kind, String provider, String extra) {
        return BASE + "/discover/" + kind + "?api_key=" + KEY + "&language=" + LANG
                + "&watch_region=" + REGION + "&with_watch_providers=" + provider
                + "&with_watch_monetization_types=flatrate%7Cfree%7Cads&sort_by=popularity.desc&" + extra;
    }

    private static List<Show> fetchList(String url, boolean movie, Map<Integer, String> genreNames) {
        List<Show> out = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(http(url)).getJSONArray("results");
            for (int i = 0; i < results.length() && out.size() < PER_KIND; i++) {
                JSONObject o = results.getJSONObject(i);
                String poster = o.optString("poster_path", "");
                if (poster.isEmpty() || poster.equals("null")) continue;
                Show s = new Show();
                s.id = o.optInt("id");
                s.name = o.optString(movie ? "title" : "name");
                s.img = POSTER + poster;
                String bd = o.optString("backdrop_path", "");
                s.bg = bd.isEmpty() || bd.equals("null") ? "" : BACKDROP + bd;
                s.summary = o.optString("overview", "").trim();
                s.rating = o.optDouble("vote_average", 0);
                String date = o.optString(movie ? "release_date" : "first_air_date", "");
                s.ep = (movie ? "Película" : "Serie") + (date.length() >= 4 ? " · " + date.substring(0, 4) : "");
                JSONArray g = o.optJSONArray("genre_ids");
                StringBuilder gb = new StringBuilder();
                for (int k = 0; g != null && k < Math.min(g.length(), 3); k++) {
                    String gn = esGenre(genreNames.get(g.optInt(k)));
                    if (gn == null) continue;
                    if (gb.length() > 0) gb.append(", ");
                    gb.append(gn);
                }
                s.genres = gb.toString();
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** TMDB devuelve algunos géneros de series en inglés aunque se pida español. */
    private static String esGenre(String g) {
        if (g == null) return null;
        switch (g) {
            case "Action & Adventure":
                return "Acción y aventura";
            case "Sci-Fi & Fantasy":
                return "Ciencia ficción y fantasía";
            case "War & Politics":
                return "Guerra y política";
            case "Kids":
                return "Infantil";
            case "News":
                return "Noticias";
            case "Soap":
                return "Telenovela";
            case "Talk":
                return "Talk show";
            default:
                return g;
        }
    }

    private static Map<Integer, String> genres(String kind) {
        Map<Integer, String> m = new HashMap<>();
        try {
            JSONArray a = new JSONObject(http(BASE + "/genre/" + kind + "/list?api_key=" + KEY
                    + "&language=" + LANG)).getJSONArray("genres");
            for (int i = 0; i < a.length(); i++) {
                m.put(a.getJSONObject(i).getInt("id"), a.getJSONObject(i).getString("name"));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static String toCache(Map<String, List<Show>> m) throws Exception {
        JSONObject root = new JSONObject();
        for (Map.Entry<String, List<Show>> en : m.entrySet()) {
            JSONArray a = new JSONArray();
            for (Show s : en.getValue()) {
                JSONObject o = new JSONObject();
                o.put("d", s.id).put("n", s.name).put("i", s.img).put("b", s.bg).put("s", s.summary)
                        .put("g", s.genres).put("l", s.lang).put("e", s.ep).put("r", s.rating);
                a.put(o);
            }
            root.put(en.getKey(), a);
        }
        return root.toString();
    }

    private static Map<String, List<Show>> parseCache(String json) throws Exception {
        Map<String, List<Show>> m = new HashMap<>();
        JSONObject root = new JSONObject(json);
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = root.getJSONArray(k);
            List<Show> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Show s = new Show();
                s.id = o.optInt("d");
                s.name = o.optString("n");
                s.img = o.optString("i");
                s.bg = o.optString("b");
                s.summary = o.optString("s");
                s.genres = o.optString("g");
                s.lang = o.optString("l");
                s.ep = o.optString("e");
                s.rating = o.optDouble("r", 0);
                l.add(s);
            }
            m.put(k, l);
        }
        return m;
    }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "PSLauncher/1.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toString("UTF-8");
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toByteArray();
        }
    }
}
