package com.leantv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Videos on the computer, listed by LeanTV Library (drop\LeanTVLibrary.ps1) at 127.0.0.1:8790 through the adb
 * tunnel, plus the ones copied onto the TV. Also remembers where each video was stopped.
 */
final class Library {

    static final String BASE = "http://127.0.0.1:8790";

    static final class Item {
        String key;          // "lib:<id>" or "file:<path>": what progress is stored under
        String url, path;        // path: only for videos stored on the TV
        String title = "", show;
        int season, episode, year;
        long size, added;
        boolean local;

        boolean isEpisode() { return show != null; }

        /** What the player shows: "Nathan for You · S2 E8 · Title" or the movie title. */
        String fullTitle() {
            if (!isEpisode()) return title;
            String t = show + "  ·  S" + season + " E" + episode;
            return title.isEmpty() ? t : t + "  ·  " + title;
        }
    }

    static final class Listing {
        boolean online;
        String computer = "";
        final List<Item> items = new ArrayList<>();
    }

    /** Blocking; call off the main thread. Never throws: an unreachable computer gives online = false. */
    static Listing fetch(Context c) {
        Listing out = new Listing();
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(BASE + "/api/library").openConnection();
            http.setConnectTimeout(1500);
            http.setReadTimeout(6000);
            if (http.getResponseCode() == 200) {
                InputStream in = http.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                JSONObject doc = new JSONObject(buf.toString("UTF-8"));
                out.computer = doc.optString("name");
                JSONArray arr = doc.optJSONArray("items");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item();
                    String id = o.getString("id");
                    it.key = "lib:" + id;
                    it.url = BASE + "/v/" + id;
                    it.title = o.optString("title");
                    if (!o.isNull("show") && !o.optString("show").isEmpty()) it.show = o.optString("show");
                    it.season = o.optInt("season");
                    it.episode = o.optInt("episode");
                    it.year = o.optInt("year");
                    it.size = o.optLong("size");
                    it.added = o.optLong("added");
                    out.items.add(it);
                }
                out.online = true;
            }
        } catch (Exception ignored) {
        } finally {
            if (http != null) http.disconnect();
        }
        for (File f : Videos.list(c)) {
            Item it = new Item();
            it.key = "file:" + f.getAbsolutePath();
            it.url = Uri.fromFile(f).toString();
            it.path = f.getAbsolutePath();
            it.title = Videos.title(f.getName());
            it.size = f.length();
            it.added = f.lastModified();
            it.local = true;
            out.items.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------------ watch progress

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("progress", Context.MODE_PRIVATE);
    }

    /** Stores "position,duration,when". Near the end counts as watched. */
    static void saveProgress(Context c, String key, int posMs, int durMs) {
        if (key == null || durMs <= 0) return;
        if (posMs > durMs * 0.94f || durMs - posMs < 45000) { markWatched(c, key, true); return; }
        if (posMs < 15000) return;
        prefs(c).edit().putString("p:" + key, posMs + "," + durMs + "," + System.currentTimeMillis()).apply();
    }

    /** Where to start, or 0. */
    static int resumeAt(Context c, String key) {
        long[] p = progress(c, key);
        return p != null ? (int) p[0] : 0;
    }

    /** {position, duration, when}, or null if never started / already watched. */
    static long[] progress(Context c, String key) {
        String v = key != null ? prefs(c).getString("p:" + key, null) : null;
        if (v == null) return null;
        try {
            String[] f = v.split(",");
            return new long[]{Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])};
        } catch (Exception e) {
            return null;
        }
    }

    /** When it was last watched to the end, or 0. */
    static long watched(Context c, String key) {
        return key != null ? prefs(c).getLong("w:" + key, 0) : 0;
    }

    static void markWatched(Context c, String key, boolean watched) {
        SharedPreferences.Editor e = prefs(c).edit().remove("p:" + key);
        if (watched) e.putLong("w:" + key, System.currentTimeMillis());
        else e.remove("w:" + key);
        e.apply();
    }

    static void clearProgress(Context c, String key) {
        prefs(c).edit().remove("p:" + key).apply();
    }

    private Library() {}
}
