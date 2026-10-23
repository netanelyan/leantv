package com.leantv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Apple TV-style tiles: rounded 5:3 cards that lift and grow on focus, with a soft sheen across the top and
 * the name fading in underneath. Unfocused cards are dimmed a little so the focused one stands out.
 */
final class Tiles {

    interface Listener {
        void onClick(Apps.App app);
        void onOptions(Apps.App app);
    }

    static final float FOCUS_SCALE = 1.16f;
    private static final float REST_ALPHA = 0.88f;
    /** tvOS-like ease-out: quick start, long soft landing. */
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.25f, 1f);

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** A dock or grid tile for an app. */
    static View make(Context c, Apps.App app, int widthDp, Listener listener) {
        return build(c, app, app.label, null, widthDp, listener, null);
    }

    /** A tile for a built-in action ("All Apps", "Videos") drawn as a frosted card with a white glyph. */
    static View action(Context c, CharSequence label, Drawable glyph, int widthDp, View.OnClickListener click) {
        return build(c, null, label, glyph, widthDp, null, click);
    }

    private static View build(final Context c, final Apps.App app, CharSequence text, Drawable glyph,
                              int widthDp, final Listener listener, final View.OnClickListener plainClick) {
        int w = dp(c, widthDp), h = w * 3 / 5, radius = dp(c, 14);

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        final FrameLayout card = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(app != null ? app.tint : 0x2EFFFFFF);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setElevation(dp(c, 3));
        card.setAlpha(REST_ALPHA);

        ImageView img = new ImageView(c);
        if (app != null && app.banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(app.banner);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(app != null ? app.icon : glyph);
            int s = app != null ? h * 11 / 20 : h * 2 / 5;
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }

        // Sheen: light falling on the top of the card, plus a hairline edge so dark banners don't melt into
        // the glass. Only fully visible on focus.
        final View sheen = new View(c);
        GradientDrawable shine = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        shine.setCornerRadius(radius);
        shine.setStroke(Math.max(1, dp(c, 1) / 2), 0x30FFFFFF);
        sheen.setBackground(shine);
        sheen.setAlpha(0.35f);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));
        column.addView(card, new LinearLayout.LayoutParams(w, h));

        final TextView label = new TextView(c);
        Fonts.style(label, Fonts.MEDIUM, 13, 0f);
        label.setText(text);
        label.setLetterSpacing(0.01f);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setTranslationY(-dp(c, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
        lp.topMargin = dp(c, 16);
        column.addView(label, lp);

        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).alpha(focused ? 1f : REST_ALPHA)
                    .translationZ(focused ? dp(c, 22) : 0).setDuration(260).setInterpolator(EASE).start();
            sheen.animate().alpha(focused ? 1f : 0.35f).setDuration(260).start();
            label.animate().alpha(focused ? 1f : 0f).translationY(focused ? 0 : -dp(c, 4))
                    .setDuration(220).setInterpolator(EASE).start();
        });

        if (app != null) {
            card.setOnClickListener(v -> listener.onClick(app));
            card.setOnLongClickListener(v -> { listener.onOptions(app); return true; });
            card.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
                    listener.onOptions(app);
                    return true;
                }
                return false;
            });
        } else {
            card.setOnClickListener(plainClick);
        }
        column.setTag(card);
        return column;
    }

    /** The focusable card inside a tile returned by {@link #make}. */
    static View card(View tile) {
        return (View) tile.getTag();
    }

    /** 3x3 dots, used for the "All Apps" tile. */
    static final class DotGrid extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotGrid() {
            p.setColor(0xF2FFFFFF);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float step = Math.min(b.width(), b.height()) / 3f, r = step * 0.16f;
            float ox = b.centerX() - step, oy = b.centerY() - step;
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
                canvas.drawCircle(ox + x * step, oy + y * step, r, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A rounded play triangle, used for the "Videos" tile and the player. */
    static final class PlayGlyph extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean pause;

        PlayGlyph(boolean pause) {
            this.pause = pause;
            p.setColor(0xF2FFFFFF);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY();
            if (pause) {
                float bw = s * 0.2f, gap = s * 0.14f, bh = s * 0.7f;
                p.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(cx - gap / 2 - bw, cy - bh / 2, cx - gap / 2, cy + bh / 2, bw / 3, bw / 3, p);
                canvas.drawRoundRect(cx + gap / 2, cy - bh / 2, cx + gap / 2 + bw, cy + bh / 2, bw / 3, bw / 3, p);
                return;
            }
            float r = s * 0.36f;
            path.reset();
            path.moveTo(cx - r * 0.75f, cy - r);
            path.lineTo(cx + r, cy);
            path.lineTo(cx - r * 0.75f, cy + r);
            path.close();
            p.setStyle(Paint.Style.FILL_AND_STROKE);
            p.setStrokeWidth(s * 0.08f);
            canvas.drawPath(path, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private Tiles() {}
}
