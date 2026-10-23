package com.leantv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.Locale;

/**
 * Full-screen video player for files copied by LeanTV Drop and for streams served from the computer.
 *
 *   am start -n com.leantv.home/.PlayerActivity -d http://127.0.0.1:8765/v/1 --es title_b64 <base64 utf-8>
 *   am start -n com.leantv.home/.PlayerActivity -d file:///sdcard/Android/data/com.leantv.home/files/videos/x.mp4
 *
 * Remote: OK / play-pause toggles, left/right (or rewind/fast-forward) jump 10 s (30 s while held),
 * up/down show the progress bar, back closes.
 */
public class PlayerActivity extends Activity {

    private static final int HIDE_MS = 3500;

    private VideoView video;
    private View overlay, flash;
    private ImageView flashGlyph;
    private TextView title, elapsed, remaining;
    private View progressFill;
    private FrameLayout progressTrack;
    private ProgressBar spinner;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean prepared;
    private int resumeAt;

    private final Runnable hide = () -> showOverlay(false);
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
        play(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        play(intent);
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xCCFFFFFF));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));

        // Centre flash: a frosted disc with a play / pause glyph, shown briefly on toggle.
        FrameLayout disc = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x55000000);
        circle.setStroke(Math.max(1, dp(1) / 2), 0x40FFFFFF);
        disc.setBackground(circle);
        flashGlyph = new ImageView(this);
        disc.addView(flashGlyph, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        disc.setAlpha(0f);
        flash = disc;
        root.addView(disc, new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER));

        // Bottom overlay: title, a thin progress line, elapsed and remaining time.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(64), dp(80), dp(64), dp(44));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x4D000000, 0x00000000}));
        title = Fonts.style(new TextView(this), Fonts.SEMIBOLD, 22, 1f);
        title.setSingleLine(true);
        bar.addView(title);

        progressTrack = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(2));
        track.setColor(0x40FFFFFF);
        progressTrack.setBackground(track);
        progressFill = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(2));
        fill.setColor(0xF2FFFFFF);
        progressFill.setBackground(fill);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(4));
        tlp.topMargin = dp(18);
        bar.addView(progressTrack, tlp);

        FrameLayout times = new FrameLayout(this);
        elapsed = Fonts.style(new TextView(this), Fonts.MEDIUM, 13, 0.8f);
        remaining = Fonts.style(new TextView(this), Fonts.MEDIUM, 13, 0.8f);
        for (TextView t : new TextView[]{elapsed, remaining}) {
            t.setFontFeatureSettings("tnum");
            t.setTextDirection(View.TEXT_DIRECTION_LTR);
        }
        times.addView(elapsed, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        times.addView(remaining, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(10);
        bar.addView(times, ilp);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        overlay = bar;
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        video.setOnPreparedListener(mp -> {
            prepared = true;
            spinner.setVisibility(View.GONE);
            if (resumeAt > 0) video.seekTo(resumeAt);
            resumeAt = 0;
            video.start();
            showOverlay(true);
        });
        video.setOnCompletionListener(mp -> {
            showOverlay(true);
            ui.postDelayed(this::finish, 1200);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, R.string.cant_play, Toast.LENGTH_LONG).show();
            finish();
            return true;
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
            if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) spinner.setVisibility(View.GONE);
            return false;
        });
        return root;
    }

    private void play(Intent i) {
        Uri uri = i != null ? i.getData() : null;
        if (uri == null) { finish(); return; }
        String t = null;
        String b64 = i.getStringExtra("title_b64");
        if (b64 != null) {
            try { t = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8"); } catch (Exception ignored) {}
        }
        if (t == null) t = i.getStringExtra("title");
        if (t == null) t = Videos.title(new File(uri.getPath() != null ? uri.getPath() : "video").getName());
        title.setText(t);
        prepared = false;
        spinner.setVisibility(View.VISIBLE);
        video.setVideoURI(uri);
        showOverlay(true);
    }

    private void toggle() {
        if (!prepared) return;
        boolean playing = video.isPlaying();
        if (playing) video.pause(); else video.start();
        flashGlyph.setImageDrawable(new Tiles.PlayGlyph(playing));
        flash.animate().cancel();
        flash.setScaleX(0.9f);
        flash.setScaleY(0.9f);
        flash.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Tiles.EASE)
                .withEndAction(() -> flash.animate().alpha(0f).setStartDelay(playing ? 700 : 350).setDuration(300).start())
                .start();
        showOverlay(true);
    }

    private void seekBy(int ms) {
        if (!prepared) return;
        int d = video.getDuration(), p = video.getCurrentPosition() + ms;
        video.seekTo(Math.max(0, d > 0 ? Math.min(p, d - 1000) : p));
        showOverlay(true);
        updateProgress();
    }

    private void showOverlay(boolean show) {
        ui.removeCallbacks(hide);
        overlay.animate().alpha(show ? 1f : 0f).setDuration(show ? 180 : 450).start();
        ui.removeCallbacks(progress);
        if (show) {
            ui.post(progress);
            // Stay up while paused; otherwise fade away.
            if (!prepared || video.isPlaying()) ui.postDelayed(hide, HIDE_MS);
        }
    }

    private void updateProgress() {
        int d = prepared ? video.getDuration() : 0, p = prepared ? video.getCurrentPosition() : 0;
        int w = progressTrack.getWidth();
        progressFill.getLayoutParams().width = d > 0 ? (int) ((long) w * p / d) : 0;
        progressFill.requestLayout();
        elapsed.setText(d > 0 ? clock(p) : "");
        remaining.setText(d > 0 ? "−" + clock(d - p) : "");
    }

    private static String clock(int ms) {
        int s = Math.max(0, ms / 1000), h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int step = event.getRepeatCount() > 0 ? 30000 : 10000;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (event.getRepeatCount() == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (prepared && !video.isPlaying()) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (prepared && video.isPlaying()) toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-step);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(step);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prepared) resumeAt = video.getCurrentPosition();
        video.pause();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prepared && resumeAt > 0) {
            video.seekTo(resumeAt);
            video.start();
            resumeAt = 0;
            showOverlay(true);
        }
    }

    @Override
    protected void onDestroy() {
        video.stopPlayback();
        super.onDestroy();
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }
}
