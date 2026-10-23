package com.leantv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rotating nature wallpapers. Two stacked layers crossfade; the visible one zooms very slowly. Each layer
 * also carries a heavily blurred copy of its photo, clipped to the dock's rounded rectangle, which is what
 * makes the dock look like frosted glass (cheap: the blur is computed once on a tiny bitmap).
 * Sources: bundled assets/wallpapers/*.jpg plus any images pushed to
 * /sdcard/Android/data/com.leantv.home/files/wallpapers/.
 */
final class Wallpapers {

    static final String KEY_INTERVAL = "interval";
    private static final int FADE_MS = 1800;
    private static final float ZOOM = 1.06f;

    private static final class Layer {
        final FrameLayout root, glass;
        final ImageView sharp, blur;
        Bitmap sharpBmp, blurBmp;

        Layer(Context c, ViewOutlineProvider glassShape) {
            root = new FrameLayout(c);
            sharp = new ImageView(c);
            sharp.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(sharp, new FrameLayout.LayoutParams(-1, -1));
            glass = new FrameLayout(c);
            glass.setOutlineProvider(glassShape);
            glass.setClipToOutline(true);
            blur = new ImageView(c);
            blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
            glass.addView(blur, new FrameLayout.LayoutParams(-1, -1));
            root.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        }

        void set(Bitmap s, Bitmap b) {
            release();
            sharpBmp = s;
            blurBmp = b;
            sharp.setImageBitmap(s);
            blur.setImageBitmap(b);
        }

        void release() {
            sharp.setImageDrawable(null);
            blur.setImageDrawable(null);
            if (sharpBmp != null) sharpBmp.recycle();
            if (blurBmp != null) blurBmp.recycle();
            sharpBmp = blurBmp = null;
        }

        void zoom(long ms) {
            for (View v : new View[]{sharp, blur}) {
                v.animate().cancel();
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.animate().scaleX(ZOOM).scaleY(ZOOM).setDuration(ms).setInterpolator(new LinearInterpolator()).start();
            }
        }
    }

    private final Context ctx;
    private final Layer back, front;
    private final TextView title, byline;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<String> items = new ArrayList<>();
    private final Map<String, String[]> credits = new HashMap<>();
    private final Rect glassRect = new Rect();
    private float glassRadius;
    private int index = -1;
    private boolean running, frontShowing, first = true;

    private final Runnable tick = this::advance;

    private final ViewOutlineProvider glassShape = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            if (glassRect.isEmpty()) outline.setEmpty();
            else outline.setRoundRect(glassRect, glassRadius);
        }
    };

    /** Adds the two photo layers to the bottom of {@code host}. */
    Wallpapers(Context ctx, FrameLayout host, TextView title, TextView byline) {
        this.ctx = ctx;
        this.title = title;
        this.byline = byline;
        back = new Layer(ctx, glassShape);
        front = new Layer(ctx, glassShape);
        host.addView(back.root, 0, new FrameLayout.LayoutParams(-1, -1));
        host.addView(front.root, 1, new FrameLayout.LayoutParams(-1, -1));
        front.root.setAlpha(0f);
        scan();
    }

    /** Where the frosted glass goes, in host coordinates. */
    void setGlass(Rect r, float radius) {
        if (r.equals(glassRect) && radius == glassRadius) return;
        glassRect.set(r);
        glassRadius = radius;
        back.glass.invalidateOutline();
        front.glass.invalidateOutline();
    }

    long intervalMs() {
        int s = ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).getInt(KEY_INTERVAL, 60);
        return Math.max(10, s) * 1000L;
    }

    private void scan() {
        items.clear();
        try {
            for (String f : ctx.getAssets().list("wallpapers"))
                if (isImage(f)) items.add("asset:wallpapers/" + f);
            InputStream in = ctx.getAssets().open("wallpapers/credits.json");
            byte[] buf = new byte[in.available()];
            int n = 0;
            while (n < buf.length) n += in.read(buf, n, buf.length - n);
            in.close();
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                credits.put("asset:wallpapers/" + o.getString("file"), new String[]{
                        o.optString("title"),
                        ctx.getString(R.string.photo_by, o.optString("artist")) + "  ·  " + o.optString("license")});
            }
        } catch (Exception ignored) {
        }
        File dir = ctx.getExternalFilesDir("wallpapers");
        File[] extra = dir != null ? dir.listFiles() : null;
        if (extra != null) for (File f : extra) if (isImage(f.getName())) items.add(f.getAbsolutePath());
        Collections.shuffle(items);
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    void start() {
        if (running || items.isEmpty()) return;
        running = true;
        if (first) advance();
        else ui.postDelayed(tick, intervalMs());
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    /** Re-read the folders (e.g. after new wallpapers were pushed) and show the next one. */
    void rescan() {
        scan();
        index = -1;
    }

    private void advance() {
        ui.removeCallbacks(tick);
        if (items.isEmpty()) return;
        index = (index + 1) % items.size();
        final String item = items.get(index);
        io.execute(() -> {
            final Bitmap bmp = decode(item);
            final Bitmap blur = bmp != null ? frost(bmp) : null;
            ui.post(() -> {
                if (bmp != null) show(bmp, blur, credits.get(item));
                if (running) ui.postDelayed(tick, intervalMs());
            });
        });
    }

    private void show(Bitmap bmp, Bitmap blur, String[] credit) {
        final Layer in = frontShowing ? back : front, out = frontShowing ? front : back;
        in.set(bmp, blur);
        in.zoom(intervalMs() + FADE_MS * 2);
        int fade = first ? 600 : FADE_MS;
        first = false;
        // The front layer fades in over the back one, or out to reveal it.
        front.root.animate().cancel();
        front.root.animate().alpha(in == front ? 1f : 0f).setDuration(fade).withEndAction(out::release).start();
        frontShowing = (in == front);

        final String t = credit != null ? credit[0] : "", b = credit != null ? credit[1] : "";
        for (final TextView v : new TextView[]{title, byline}) {
            final float a = v == title ? 0.92f : 0.68f;
            v.animate().alpha(0f).setDuration(fade / 2).withEndAction(() -> {
                v.setText(v == title ? t : b);
                v.animate().alpha(t.isEmpty() ? 0f : a).setDuration(fade / 2).start();
            }).start();
        }
    }

    private Bitmap decode(String item) {
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int tw = Math.max(dm.widthPixels, dm.heightPixels), th = Math.min(dm.widthPixels, dm.heightPixels);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(item)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b;
            try (InputStream in = open(item)) { b = BitmapFactory.decodeStream(in, null, o); }
            // Many TV boxes draw the UI at 720p: don't keep 1080p pixels nobody sees.
            if (b != null && b.getWidth() > tw * 1.15f) {
                int h = Math.round(b.getHeight() * (tw / (float) b.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(b, tw, h, true);
                if (scaled != b) b.recycle();
                b = scaled;
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A 1/12-size, box-blurred copy. Drawn scaled back up to full screen it reads as a deep blur. */
    private static Bitmap frost(Bitmap src) {
        try {
            int w = Math.max(16, src.getWidth() / 12), h = Math.max(9, src.getHeight() / 12);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            if (small == src) small = src.copy(Bitmap.Config.ARGB_8888, true);
            int[] px = new int[w * h], tmp = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(px, tmp, w, h, 3, true);
                boxBlur(tmp, px, w, h, 3, false);
            }
            Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
            out.setPixels(px, 0, w, 0, 0, w, h);
            if (out != small) small.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[idx(line, clamp(k, len), w, horizontal)];
                sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            int div = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[idx(line, clamp(i - r, len), w, horizontal)];
                int pIn = in[idx(line, clamp(i + r + 1, len), w, horizontal)];
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private InputStream open(String item) throws Exception {
        if (item.startsWith("asset:")) return ctx.getAssets().open(item.substring(6));
        return new FileInputStream(item);
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }
}
