package com.gathin.tvhome;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cinemeta (Stremio's free metadata service) for the picks: a watched title's genres and people, and the
 * top and best-rated titles of a genre to choose from. Responses are streamed (a series catalog page is
 * about 600 KB, mostly episode lists we skip) and kept on disk, compacted, for a day; when Cinemeta can't be
 * reached the last copy is used however old. Blocking: call off the main thread.
 */
final class Cinemeta {

    private static final String TAG = "HomeShelf";
    private static final String BASE = "https://v3-cinemeta.strem.io/";
    private static final long FRESH_MS = 24L * 60 * 60 * 1000;

    private final File dir;

    Cinemeta(File cacheDir) {
        dir = new File(cacheDir, "cinemeta");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** The picks for these seeds, leaving out anything in exclude (everything watched). */
    List<Picks.Pick> picks(List<Picks.Seed> seeds, Set<String> exclude) {
        List<List<Picks.Pick>> perSeed = new ArrayList<>();
        for (Picks.Seed seed : seeds) {
            List<Picks.Title> meta = titles(BASE + "meta/" + seed.type + "/" + Uri.encode(seed.id) + ".json", true);
            if (meta.isEmpty()) continue;
            Picks.Title t = meta.get(0);
            List<Picks.Title> candidates = new ArrayList<>();
            for (String genre : t.searchGenres()) {
                String g = Uri.encode(genre);
                candidates.addAll(titles(BASE + "catalog/" + seed.type + "/top/genre=" + g + ".json", false));
                candidates.addAll(titles(BASE + "catalog/" + seed.type + "/imdbRating/genre=" + g + ".json", false));
            }
            perSeed.add(Picks.rank(t, seed.name != null ? seed.name : t.name, candidates, exclude));
        }
        return Picks.merge(perSeed, Picks.MAX_PICKS);
    }

    /** The titles at url (one for a meta, a page for a catalog), from the disk copy when fresh. */
    private List<Picks.Title> titles(String url, boolean single) {
        File f = cacheFile(url);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) {
            List<Picks.Title> cached = read(f);
            if (!cached.isEmpty()) return cached;
        }
        long started = SystemClock.elapsedRealtime();
        try {
            List<JSONObject> parsed = fetch(url, single);
            JSONArray a = new JSONArray();
            for (JSONObject o : parsed) a.put(o);
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            return toTitles(parsed);
        } catch (Exception e) {
            Log.w(TAG, "failed after " + (SystemClock.elapsedRealtime() - started) + " ms: " + url + ": " + e);
            return f.exists() ? read(f) : Collections.emptyList();
        }
    }

    private List<Picks.Title> read(File f) {
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) list.add(a.getJSONObject(i));
            return toTitles(list);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<Picks.Title> toTitles(List<JSONObject> list) {
        List<Picks.Title> out = new ArrayList<>();
        for (JSONObject o : list) {
            out.add(new Picks.Title(o.optString("id"), o.optString("type"), o.optString("name"),
                    nonEmpty(o.optString("poster")), nonEmpty(o.optString("background")),
                    strings(o.optJSONArray("genres")), strings(o.optJSONArray("makers")),
                    strings(o.optJSONArray("cast")), o.optString("country"), o.optDouble("rating", 0), o.optInt("year")));
        }
        return out;
    }

    /** Streams a meta ({"meta": {...}}) or catalog ({"metas": [...]}) response into compact objects. */
    private static List<JSONObject> fetch(String url, boolean single) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream();
                 JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<JSONObject> out = new ArrayList<>();
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (single && "meta".equals(key) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        JSONObject o = readMeta(r);
                        if (o != null) out.add(o);
                    } else if (!single && "metas".equals(key) && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            JSONObject o = readMeta(r);
                            if (o != null) out.add(o);
                        }
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                return out;
            }
        } finally {
            c.disconnect();
        }
    }

    /** One meta object, keeping only what the picks use; null without an IMDb id. */
    private static JSONObject readMeta(JsonReader r) throws IOException, JSONException {
        String id = null, imdb = null, type = null, name = null, poster = null, background = null, country = null;
        String rating = null, year = null;
        List<String> genres = new ArrayList<>(), makers = new ArrayList<>(), cast = new ArrayList<>();
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            switch (key) {
                case "id": id = string(r); break;
                case "imdb_id": imdb = string(r); break;
                case "type": type = string(r); break;
                case "name": name = string(r); break;
                case "poster": poster = string(r); break;
                case "background": background = string(r); break;
                case "country": country = string(r); break;
                case "imdbRating": rating = string(r); break;
                case "year": year = string(r); break;
                case "releaseInfo": if (year == null) year = string(r); else r.skipValue(); break;
                case "genres": genres = stringArray(r); break;
                case "genre": if (genres.isEmpty()) genres = stringArray(r); else r.skipValue(); break;
                case "director": makers.addAll(stringArray(r)); break;
                case "writer": makers.addAll(stringArray(r)); break;
                case "cast": cast = stringArray(r); break;
                default: r.skipValue();
            }
        }
        r.endObject();
        String tt = imdb != null && imdb.startsWith("tt") ? imdb : id;
        if (tt == null || !tt.startsWith("tt") || name == null) return null;
        JSONArray ordered = new JSONArray(genres);
        return new JSONObject().put("id", tt).put("type", type).put("name", name).put("poster", poster)
                .put("background", background).put("country", country)
                .put("rating", parseDouble(rating)).put("year", firstYear(year))
                .put("genres", ordered)
                .put("makers", new JSONArray(makers)).put("cast", new JSONArray(cast.size() > 6 ? cast.subList(0, 6) : cast));
    }

    private static String string(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        if (t == JsonToken.NUMBER) return r.nextString();
        r.skipValue();
        return null;
    }

    private static List<String> stringArray(JsonReader r) throws IOException {
        List<String> out = new ArrayList<>();
        if (r.peek() == JsonToken.STRING) {
            out.add(r.nextString());
            return out;
        }
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue();
            return out;
        }
        r.beginArray();
        while (r.hasNext()) {
            String s = string(r);
            if (s != null) out.add(s);
        }
        r.endArray();
        return out;
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 2018 from "2018–2023" or "2018". */
    private static int firstYear(String s) {
        if (s == null || s.length() < 4) return 0;
        try {
            return Integer.parseInt(s.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private File cacheFile(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return new File(dir, b.toString());
        } catch (Exception e) {
            return new File(dir, Integer.toHexString(url.hashCode()));
        }
    }

    /** Ids already seen, for the exclude set. */
    static Set<String> ids(List<LayoverFeed.Watched> history) {
        Set<String> out = new HashSet<>();
        for (LayoverFeed.Watched w : history) if (w.id != null) out.add(w.id);
        return out;
    }
}
