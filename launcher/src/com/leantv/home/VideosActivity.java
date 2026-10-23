package com.leantv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Videos copied to the TV by LeanTV Drop, newest first. OK plays; long-press (or Menu) deletes. */
public class VideosActivity extends Activity {

    private static final int COLUMNS = 4;
    private LinearLayout grid;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A2129, 0xFF0D1116, 0xFF07090C}));
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(64), dp(44), dp(64), dp(56));

        TextView title = Fonts.style(new TextView(this), Fonts.SEMIBOLD, 26, 0.95f);
        title.setText(R.string.videos_title);
        title.setLetterSpacing(-0.01f);
        content.addView(title);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        content.addView(grid);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        grid.removeAllViews();
        List<File> files = Videos.list(this);
        if (files.isEmpty()) {
            TextView empty = Fonts.style(new TextView(this), Fonts.REGULAR, 16, 0.6f);
            empty.setText(R.string.videos_empty);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(24);
            grid.addView(empty, lp);
            return;
        }
        int w = Math.min(220, (getResources().getConfiguration().screenWidthDp - 128) / COLUMNS - 32);
        LinearLayout row = null;
        for (int i = 0; i < files.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.topMargin = dp(24);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), -2);
            lp.rightMargin = dp(32);
            row.addView(card(files.get(i), w), lp);
        }
        ((LinearLayout) grid.getChildAt(0)).getChildAt(0).findViewWithTag("card").requestFocus();
    }

    private View card(final File f, int widthDp) {
        int w = dp(widthDp), h = w * 9 / 16;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        final FrameLayout card = new FrameLayout(this);
        card.setTag("card");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x1FFFFFFF);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setElevation(dp(3));
        final ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new Tiles.PlayGlyph(false));
        glyph.setAlpha(0.85f);
        card.addView(glyph, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        final TextView duration = Fonts.style(new TextView(this), Fonts.MEDIUM, 11, 0.95f);
        duration.setFontFeatureSettings("tnum");
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(8));
        pill.setColor(0x99000000);
        duration.setBackground(pill);
        duration.setPadding(dp(7), dp(3), dp(7), dp(3));
        duration.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        dlp.setMargins(0, 0, dp(8), dp(8));
        card.addView(duration, dlp);
        column.addView(card, new LinearLayout.LayoutParams(w, h));

        TextView name = Fonts.style(new TextView(this), Fonts.MEDIUM, 13, 0.75f);
        name.setText(Videos.title(f.getName()));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(12);
        column.addView(name, nlp);

        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(18) : 0)
                    .setDuration(240).setInterpolator(Tiles.EASE).start();
            name.animate().alpha(focused ? 1f : 0.75f).setDuration(200).start();
        });
        card.setOnClickListener(v -> startActivity(new Intent(this, PlayerActivity.class).setData(Uri.fromFile(f))));
        card.setOnLongClickListener(v -> { confirmDelete(f); return true; });
        card.setOnKeyListener((v, keyCode, e) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { confirmDelete(f); return true; }
            return false;
        });

        io.execute(() -> {
            Bitmap frame = null;
            String len = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long ms = d != null ? Long.parseLong(d) : 0;
                if (ms > 0) len = clock(ms);
                frame = r.getFrameAtTime(Math.min(ms * 1000 / 10, 20_000_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null && frame.getWidth() > w) {
                    Bitmap s = Bitmap.createScaledBitmap(frame, w, Math.max(1, frame.getHeight() * w / frame.getWidth()), true);
                    if (s != frame) frame.recycle();
                    frame = s;
                }
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
            final Bitmap shown = frame;
            final String l = len;
            runOnUiThread(() -> {
                if (shown != null) thumb.setImageBitmap(shown);
                if (l != null) { duration.setText(l); duration.setVisibility(View.VISIBLE); }
            });
        });
        return column;
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Videos.title(f.getName()))
                .setItems(new CharSequence[]{getString(R.string.delete)}, (d, which) -> {
                    if (f.delete()) build();
                })
                .show();
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }
}
