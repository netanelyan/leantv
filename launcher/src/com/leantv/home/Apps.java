package com.leantv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable-app discovery, the dock list, and a small drawable cache. */
final class Apps {

    static final String PREFS = "leantv";
    static final String KEY_DOCK = "dock";
    static final String SETTINGS_ID = "@settings";

    /** Used only until the dock has been configured once. Filtered to what is installed. */
    private static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.apple.atve.androidtv.appletv",
            "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic",
            "com.plexapp.android",
            "org.xbmc.kodi",
    };

    static final class App {
        final String id;          // package name, or SETTINGS_ID
        final CharSequence label;
        final Intent launch;
        final Drawable banner;    // 16:9-ish art, may be null
        final Drawable icon;
        final int tint;           // fallback tile colour derived from the icon

        App(String id, CharSequence label, Intent launch, Drawable banner, Drawable icon) {
            this.id = id;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
            this.tint = averageColor(icon);
        }
    }

    private static Map<String, App> cache;
    private static Set<String> cachedPackages;

    static synchronized void invalidate() {
        cache = null;
    }

    /**
     * Cheap check (no icon loading) that the cache still matches what is launchable. Catches apps that
     * became available after we built it, e.g. right after boot, without relying on broadcasts.
     */
    static synchronized boolean refreshIfStale(Context ctx) {
        if (cache == null) return true;
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> found = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        collect(pm, Intent.CATEGORY_LAUNCHER, found);
        found.remove(ctx.getPackageName());
        if (found.keySet().equals(cachedPackages)) return false;
        cache = null;
        return true;
    }

    /** All launchable apps keyed by package, sorted by label. TV (leanback) entry points win. */
    static synchronized Map<String, App> all(Context ctx) {
        if (cache != null) return cache;
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> found = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        collect(pm, Intent.CATEGORY_LAUNCHER, found);
        found.remove(ctx.getPackageName());
        cachedPackages = new HashSet<>(found.keySet());

        List<App> list = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            App a = load(pm, e.getKey(), e.getValue());
            if (a != null) list.add(a);
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(list, (x, y) -> collator.compare(x.label.toString(), y.label.toString()));

        Map<String, App> map = new LinkedHashMap<>();
        for (App a : list) map.put(a.id, a);
        App settings = settingsApp(ctx);
        if (settings != null) map.put(SETTINGS_ID, settings);
        cache = map;
        return map;
    }

    private static void collect(PackageManager pm, String category, Map<String, ResolveInfo> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!out.containsKey(pkg)) out.put(pkg, ri);
        }
    }

    private static App load(PackageManager pm, String pkg, ResolveInfo ri) {
        try {
            ActivityInfo ai = ri.activityInfo;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(ri.filter != null && ri.filter.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                            ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            return new App(pkg, ri.loadLabel(pm), launch, banner, ri.loadIcon(pm));
        } catch (Exception e) {
            return null;
        }
    }

    private static App settingsApp(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo ri = pm.resolveActivity(i, 0);
        if (ri == null) return null;
        Drawable banner = ri.activityInfo.loadBanner(pm);
        return new App(SETTINGS_ID, ctx.getString(R.string.settings), i, banner, ri.loadIcon(pm));
    }

    // ---- dock ----

    static List<String> dock(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY_DOCK, null);
        Map<String, App> all = all(ctx);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            for (String pkg : DEFAULT_DOCK) if (all.containsKey(pkg)) out.add(pkg);
            if (all.containsKey(SETTINGS_ID)) out.add(SETTINGS_ID);
            return out;
        }
        for (String pkg : raw.split(",")) {
            pkg = pkg.trim();
            if (!pkg.isEmpty() && all.containsKey(pkg) && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    static void saveDock(Context ctx, List<String> dock) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DOCK, TextUtils.join(",", dock)).apply();
    }

    static void saveDock(Context ctx, String csv) {
        saveDock(ctx, new ArrayList<>(Arrays.asList(csv.split(","))));
    }

    static void resetDock(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DOCK).apply();
    }

    static Intent appInfo(String pkg) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---- helpers ----

    private static int averageColor(Drawable d) {
        if (d == null) return 0xFF2A2F36;
        try {
            Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 8, 8);
            copy.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++;
            }
            b.recycle();
            if (n == 0) return 0xFF2A2F36;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[1] = Math.min(hsv[1], 0.65f);
            hsv[2] = Math.min(hsv[2] * 0.55f, 0.38f) + 0.08f;   // keep it dark so the icon pops
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF2A2F36;
        }
    }

    private Apps() {}
}
