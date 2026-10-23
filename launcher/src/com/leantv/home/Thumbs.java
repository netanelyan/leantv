package com.leantv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Poster frames for library cards: a frame from a little way into the video, grabbed once (over the stream for
 * videos on the computer) and kept as a small JPEG in the cache folder, so later visits are instant.
 * The video's length is remembered alongside it.
 */
final class Thumbs {

    interface Callback {
        void done(Bitmap frame, long durationMs);
    }

    private final Context ctx;
    private final int width;
    private final File dir;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> failed = new HashSet<>();
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    Thumbs(Context c, int widthPx) {
        ctx = c.getApplicationContext();
        width = widthPx;
        dir = new File(ctx.getCacheDir(), "thumbs");
        dir.mkdirs();
    }

    static long duration(Context c, String key) {
        return c.getSharedPreferences("durations", Context.MODE_PRIVATE).getLong(key, 0);
    }

    void load(final Library.Item item, final Callback cb) {
        final String name = Integer.toHexString(item.key.hashCode()) + "_" + item.size + ".jpg";
        Bitmap hit = mem.get(name);
        if (hit != null) { cb.done(hit, duration(ctx, item.key)); return; }
        if (failed.contains(name)) return;
        io.execute(() -> {
            Bitmap b = null;
            long dur = duration(ctx, item.key);
            File f = new File(dir, name);
            if (f.exists()) b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if (item.local) r.setDataSource(item.path);
                    else r.setDataSource(item.url, new HashMap<>());
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    dur = d != null ? Long.parseLong(d) : 0;
                    // Past any cold open or title card, but not so far it spoils anything.
                    long at = dur > 0 ? Math.min(dur / 5, 8 * 60_000L) : 30_000L;
                    Bitmap frame = r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        int h = Math.max(1, frame.getHeight() * width / frame.getWidth());
                        b = Bitmap.createScaledBitmap(frame, width, h, true);
                        if (b != frame) frame.recycle();
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            b.compress(Bitmap.CompressFormat.JPEG, 86, out);
                        }
                    }
                    if (dur > 0) ctx.getSharedPreferences("durations", Context.MODE_PRIVATE).edit()
                            .putLong(item.key, dur).apply();
                } catch (Throwable ignored) {
                } finally {
                    try { r.release(); } catch (Throwable ignored) {}
                }
            }
            final Bitmap shown = b;
            final long length = dur;
            ui.post(() -> {
                if (shown != null) mem.put(name, shown); else failed.add(name);
                cb.done(shown, length);
            });
        });
    }

    void shutdown() {
        io.shutdownNow();
    }
}
