package com.leantv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The library: Continue Watching (half-watched videos and the next episode of shows you are watching), one row
 * per show with its episodes in order, Movies, and videos copied onto the TV.
 * OK plays (episodes then carry on to the next one); long-press or Menu for more.
 */
public class VideosActivity extends Activity {

    private LinearLayout rows;
    private TextView subtitle;
    private Thumbs thumbs;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Library.Listing listing;
    private int cardDp;
    private boolean loading;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A2129, 0xFF0D1116, 0xFF07090C}));
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(52), dp(40), dp(52), dp(56));
        // Left to right in every language, like the dock; Hebrew titles still read correctly within their line.
        content.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        TextView title = label(Fonts.SEMIBOLD, 26, 0.95f);
        title.setText(R.string.videos);
        title.setLetterSpacing(-0.01f);
        title.setPadding(dp(12), 0, dp(12), 0);
        content.addView(title);
        subtitle = label(Fonts.REGULAR, 13, 0.55f);
        subtitle.setPadding(dp(12), dp(4), dp(12), 0);
        content.addView(subtitle);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        content.addView(rows);
        scroll.addView(content);
        setContentView(scroll);

        int screen = getResources().getConfiguration().screenWidthDp;
        cardDp = Math.max(170, Math.min(250, (screen - 104) / 4 - 28));
        thumbs = new Thumbs(this, dp(cardDp));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listing != null) build();           // progress may have changed while playing
        refresh();
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        if (listing == null) subtitle.setText("");
        net.execute(() -> {
            final Library.Listing l = Library.fetch(this);
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                listing = l;
                build();
            });
        });
    }

    // ------------------------------------------------------------------ rows

    private void build() {
        View focused = getCurrentFocus();
        Object focusKey = focused != null ? focused.getTag() : null;
        rows.removeAllViews();
        firstKey = null;

        int remote = 0;
        for (Library.Item it : listing.items) if (!it.local) remote++;
        if (listing.online) subtitle.setText(getString(R.string.library_online, listing.computer, remote));
        else subtitle.setText(R.string.library_offline);

        Map<String, List<Library.Item>> shows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Library.Item> movies = new ArrayList<>(), local = new ArrayList<>();
        for (Library.Item it : listing.items) {
            if (it.local) local.add(it);
            else if (it.isEpisode()) {
                List<Library.Item> eps = shows.get(it.show);
                if (eps == null) shows.put(it.show, eps = new ArrayList<>());
                eps.add(it);
            } else movies.add(it);
        }
        for (List<Library.Item> eps : shows.values())
            Collections.sort(eps, (a, b) -> a.season != b.season ? a.season - b.season : a.episode - b.episode);
        Collections.sort(movies, (a, b) -> Long.compare(b.added, a.added));

        List<Library.Item> cont = continueWatching(shows, movies, local);
        if (!cont.isEmpty()) addRow(getString(R.string.continue_watching), null, cont, true, shows);
        for (Map.Entry<String, List<Library.Item>> e : shows.entrySet())
            addRow(e.getKey(), getResources().getQuantityString(R.plurals.episodes, e.getValue().size(), e.getValue().size()),
                    e.getValue(), false, shows);
        if (!movies.isEmpty()) addRow(getString(R.string.movies), null, movies, false, shows);
        if (!local.isEmpty()) addRow(getString(R.string.on_this_tv), null, local, false, shows);

        if (rows.getChildCount() == 0) {
            TextView empty = label(Fonts.REGULAR, 16, 0.6f);
            empty.setText(listing.online ? R.string.videos_empty : R.string.library_offline_help);
            empty.setPadding(dp(12), dp(28), dp(12), 0);
            rows.addView(empty);
            return;
        }
        View again = focusKey != null ? rows.findViewWithTag(focusKey) : null;
        if (again == null) again = rows.findViewWithTag(firstKey);
        if (again != null) again.requestFocus();
    }

    private Object firstKey;

    /** Half-watched videos, plus the episode after the last one finished in each show, most recent first. */
    private List<Library.Item> continueWatching(Map<String, List<Library.Item>> shows,
                                                List<Library.Item> movies, List<Library.Item> local) {
        final Map<Library.Item, Long> when = new LinkedHashMap<>();
        List<Library.Item> singles = new ArrayList<>(movies);
        singles.addAll(local);
        for (Library.Item it : singles) {
            long[] p = Library.progress(this, it.key);
            if (p != null) when.put(it, p[2]);
        }
        for (List<Library.Item> eps : shows.values()) {
            Library.Item best = null;
            long bestWhen = 0;
            for (int i = 0; i < eps.size(); i++) {
                Library.Item it = eps.get(i);
                long[] p = Library.progress(this, it.key);
                if (p != null && p[2] > bestWhen) { best = it; bestWhen = p[2]; }
                long w = Library.watched(this, it.key);
                if (w > bestWhen && i + 1 < eps.size()) {
                    Library.Item next = eps.get(i + 1);
                    if (Library.watched(this, next.key) == 0) { best = next; bestWhen = w; }
                }
            }
            if (best != null) when.put(best, bestWhen);
        }
        List<Library.Item> out = new ArrayList<>(when.keySet());
        Collections.sort(out, (a, b) -> Long.compare(when.get(b), when.get(a)));
        return out;
    }

    private void addRow(String name, String count, List<Library.Item> items, boolean showNames,
                        Map<String, List<Library.Item>> shows) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(dp(12), dp(rows.getChildCount() == 0 ? 26 : 18), dp(12), 0);
        TextView h = label(Fonts.SEMIBOLD, 18, 0.92f);
        h.setText(name);
        header.addView(h);
        if (count != null) {
            TextView c = label(Fonts.MEDIUM, 12, 0.45f);
            c.setText(count);
            c.setPadding(dp(10), 0, 0, dp(2));
            header.addView(c);
        }
        rows.addView(header);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(dp(12), dp(16), dp(12), dp(10));
        strip.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (Library.Item it : items) {
            List<Library.Item> eps = it.isEpisode() ? shows.get(it.show) : null;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(cardDp), -2);
            lp.rightMargin = dp(28);
            strip.addView(card(it, showNames, eps), lp);
        }
        scroller.addView(strip);
        rows.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ cards

    private View card(final Library.Item item, boolean showName, final List<Library.Item> episodes) {
        int w = dp(cardDp), h = w * 9 / 16;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        final FrameLayout card = new FrameLayout(this);
        final String tag = (showName ? "c:" : "r:") + item.key;
        card.setTag(tag);
        if (firstKey == null) firstKey = tag;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A323C, 0xFF171C22});
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setElevation(dp(3));

        final ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        final ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new Tiles.PlayGlyph(false));
        glyph.setAlpha(0.5f);
        card.addView(glyph, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));

        // Watch progress along the bottom edge.
        long[] p = Library.progress(this, item.key);
        if (p != null && p[1] > 0) {
            FrameLayout track = new FrameLayout(this);
            track.setBackgroundColor(0x59000000);
            View fill = new View(this);
            fill.setBackgroundColor(0xF2FFFFFF);
            track.addView(fill, new FrameLayout.LayoutParams((int) (w * p[0] / p[1]), -1));
            card.addView(track, new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM));
        }
        column.addView(card, new LinearLayout.LayoutParams(w, h));

        final TextView line1 = label(Fonts.MEDIUM, 14, 0.8f);
        final TextView line2 = label(Fonts.REGULAR, 12, 0.5f);
        for (TextView t : new TextView[]{line1, line2}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
        }
        line1.setText(primary(item, showName));
        line2.setText(secondary(item, showName, Thumbs.duration(this, item.key)));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-1, -2);
        l1.topMargin = dp(12);
        column.addView(line1, l1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, -2);
        l2.topMargin = dp(2);
        column.addView(line2, l2);

        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.09f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(18) : 0)
                    .setDuration(240).setInterpolator(Tiles.EASE).start();
            line1.animate().alpha(focused ? 1f : 0.8f).setDuration(200).start();
        });
        card.setOnClickListener(v -> play(item, episodes, false));
        card.setOnLongClickListener(v -> { options(item, episodes); return true; });
        card.setOnKeyListener((v, keyCode, e) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { options(item, episodes); return true; }
            return false;
        });

        thumbs.load(item, (Bitmap frame, long dur) -> {
            if (frame != null) { thumb.setImageBitmap(frame); glyph.setVisibility(View.GONE); }
            line2.setText(secondary(item, showName, dur));
        });
        return column;
    }

    private String primary(Library.Item it, boolean showName) {
        if (it.isEpisode()) {
            if (showName) return it.show;
            return it.title.isEmpty() ? getString(R.string.episode_n, it.episode) : it.title;
        }
        return it.title;
    }

    private String secondary(Library.Item it, boolean showName, long durMs) {
        StringBuilder b = new StringBuilder();
        if (it.isEpisode()) {
            if (showName || !it.title.isEmpty()) b.append(getString(R.string.season_episode, it.season, it.episode));
            else b.append(getString(R.string.season_n, it.season));
            if (showName && !it.title.isEmpty()) b.append("  ·  ").append(it.title);
        } else if (it.year > 0) {
            b.append(it.year);
        }
        long[] p = Library.progress(this, it.key);
        String time = null;
        if (p != null && p[1] > 0) time = getString(R.string.minutes_left, Math.max(1, (p[1] - p[0]) / 60000));
        else if (Library.watched(this, it.key) > 0) time = getString(R.string.watched);
        else if (durMs > 0) time = getString(R.string.minutes, Math.max(1, durMs / 60000));
        if (time != null) b.append(b.length() > 0 ? "  ·  " : "").append(time);
        return b.toString();
    }

    // ------------------------------------------------------------------ actions

    private void play(Library.Item item, List<Library.Item> episodes, boolean fromStart) {
        if (fromStart) Library.clearProgress(this, item.key);
        Intent i = new Intent(this, PlayerActivity.class)
                .setData(android.net.Uri.parse(item.url))
                .putExtra("title", item.fullTitle())
                .putExtra("key", item.key);
        // Episodes carry on into the rest of the show.
        if (episodes != null) {
            int at = episodes.indexOf(item);
            int n = Math.max(0, Math.min(episodes.size() - at - 1, 40));
            String[] urls = new String[n], keys = new String[n], titles = new String[n];
            for (int k = 0; k < n; k++) {
                Library.Item e = episodes.get(at + 1 + k);
                urls[k] = e.url; keys[k] = e.key; titles[k] = e.fullTitle();
            }
            i.putExtra("queue_urls", urls).putExtra("queue_keys", keys).putExtra("queue_titles", titles);
        }
        startActivity(i);
    }

    private void options(final Library.Item item, final List<Library.Item> episodes) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (Library.progress(this, item.key) != null) {
            labels.add(getString(R.string.play_from_start));
            actions.add(() -> play(item, episodes, true));
        }
        if (Library.watched(this, item.key) > 0) {
            labels.add(getString(R.string.mark_unwatched));
            actions.add(() -> { Library.markWatched(this, item.key, false); build(); });
        } else {
            labels.add(getString(R.string.mark_watched));
            actions.add(() -> { Library.markWatched(this, item.key, true); build(); });
        }
        if (item.local) {
            labels.add(getString(R.string.delete));
            actions.add(() -> { if (new File(item.path).delete()) refresh(); });
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(item.fullTitle())
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        thumbs.shutdown();
        super.onDestroy();
    }

    /** Text aligned to the left edge of the (always left-to-right) page, whatever language it is in. */
    private TextView label(String weight, float sp, float alpha) {
        TextView t = Fonts.style(new TextView(this), weight, sp, alpha);
        t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        t.setGravity(Gravity.LEFT);
        return t;
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }
}
