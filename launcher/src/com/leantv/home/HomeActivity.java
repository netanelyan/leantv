package com.leantv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Apple TV-style home: full-screen rotating nature photo, a large clock, and one frosted-glass dock
 * of favourite apps.
 *
 * Configurable over adb, e.g.
 *   am start -n com.leantv.home/.HomeActivity --es dock "com.netflix.ninja,com.google.android.youtube.tv,@settings"
 *   am start -n com.leantv.home/.HomeActivity --ei interval 90
 *   am start -n com.leantv.home/.HomeActivity --ez reset_dock true
 *   am start -n com.leantv.home/.HomeActivity --ez rescan true      (after pushing new wallpapers)
 */
public class HomeActivity extends Activity implements Tiles.Listener {

    private static final int SHELF_PAD = 22, TILE_MARGIN = 9, SHELF_RADIUS = 30;

    private Wallpapers wallpapers;
    private FrameLayout shelf;
    private LinearLayout dock;
    private HorizontalScrollView dockScroll;
    private boolean dirty = true;
    private String shownDock;
    private boolean resumed;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Apps.invalidate();
            dirty = true;
            if (resumed) rebuildDock();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildLayout());
        applyExtras(getIntent());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        // Scrims: a soft cinematic fall-off top and bottom keeps text and the dock legible on bright photos.
        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x47000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(300), Gravity.TOP));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x8C000000, 0x33000000, 0x00000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(340), Gravity.BOTTOM));

        // Clock (top start): large and light, date small and tracked out underneath.
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("H:mm");
        time.setFormat12Hour("h:mm");
        Fonts.style(time, Fonts.EXTRA_LIGHT, 92, 0.96f);
        time.setLetterSpacing(-0.035f);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE  d MMMM");
        date.setFormat12Hour("EEEE  d MMMM");
        Fonts.style(date, Fonts.MEDIUM, 13, 0.75f);
        date.setAllCaps(true);
        date.setLetterSpacing(0.22f);
        clock.addView(time);
        LinearLayout.LayoutParams dlpar = new LinearLayout.LayoutParams(-2, -2);
        dlpar.topMargin = dp(6);
        dlpar.leftMargin = dp(4);
        clock.addView(date, dlpar);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clp.setMargins(dp(60), dp(40), dp(48), 0);
        root.addView(clock, clp);

        // Photo credit (top end): place name, then photographer and licence, both quiet.
        LinearLayout credit = new LinearLayout(this);
        credit.setOrientation(LinearLayout.VERTICAL);
        credit.setGravity(Gravity.END);
        TextView place = Fonts.style(new TextView(this), Fonts.MEDIUM, 13, 0f);
        place.setLetterSpacing(0.02f);
        TextView byline = Fonts.style(new TextView(this), Fonts.REGULAR, 11, 0f);
        byline.setLetterSpacing(0.03f);
        for (TextView t : new TextView[]{place, byline}) {
            t.setMaxWidth(dp(420));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        place.setGravity(Gravity.END);
        byline.setGravity(Gravity.END);
        credit.addView(place);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(5);
        credit.addView(byline, blp);
        FrameLayout.LayoutParams crp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        crp.setMargins(0, dp(58), dp(60), 0);
        root.addView(credit, crp);

        // Dock: a frosted-glass shelf. The blur itself is drawn by Wallpapers, clipped to this rectangle;
        // the shelf adds a faint tint, a top highlight and a hairline edge on top of it.
        shelf = new FrameLayout(this);
        // The shelf clips the dock: a ScrollView without padding doesn't clip its own content.
        shelf.setClipChildren(true);
        shelf.setBackground(glass());
        dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        // Clip so tiles scrolled out of view stay inside the glass; the dock's top padding leaves room
        // for the focused tile to grow.
        dockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // LTR so the order you choose is the order you see, regardless of locale.
        dockScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        // A fading edge keeps focus scrolling clear of the shelf border (HorizontalScrollView ignores
        // padding when it scrolls a child into view) and softens tiles sliding under the edge.
        dockScroll.setHorizontalFadingEdgeEnabled(true);
        dockScroll.setFadingEdgeLength(dp(SHELF_PAD + TILE_MARGIN));
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.TOP);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(SHELF_PAD - TILE_MARGIN), dp(20), dp(SHELF_PAD - TILE_MARGIN), dp(4));
        dockScroll.addView(dock, new FrameLayout.LayoutParams(-2, -2));
        shelf.addView(dockScroll, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = dp(40);
        root.addView(shelf, slp);

        wallpapers = new Wallpapers(this, root, place, byline);
        shelf.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                wallpapers.setGlass(new Rect(l, t, r, b), dp(SHELF_RADIUS)));
        return root;
    }

    private Drawable glass() {
        GradientDrawable tint = new GradientDrawable();
        tint.setCornerRadius(dp(SHELF_RADIUS));
        tint.setColor(0x1CFFFFFF);
        tint.setStroke(Math.max(1, dp(1) / 2), 0x38FFFFFF);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x14FFFFFF, 0x00000000, 0x2E000000});
        shade.setCornerRadius(dp(SHELF_RADIUS));
        return new LayerDrawable(new Drawable[]{shade, tint});
    }

    private void rebuildDock() {
        dirty = false;
        View focused = getCurrentFocus();
        int focusIndex = focused != null ? indexOfCard(focused) : 0;

        dock.removeAllViews();
        Map<String, Apps.App> all = Apps.all(this);
        List<String> ids = Apps.dock(this);
        shownDock = ids.toString();
        int tiles = ids.size() + 2;

        // Big tiles; the shelf grows to fit them and scrolls once it reaches the screen width.
        int screenDp = getResources().getConfiguration().screenWidthDp;
        int maxInner = screenDp - 2 * 48 - 2 * (SHELF_PAD - TILE_MARGIN);
        int w = Math.max(124, Math.min(164, maxInner / tiles - 2 * TILE_MARGIN));
        for (String id : ids) {
            Apps.App a = all.get(id);
            if (a != null) addTile(Tiles.make(this, a, w, this));
        }
        addTile(Tiles.action(this, getString(R.string.videos), new Tiles.PlayGlyph(false), w,
                v -> startActivity(new Intent(this, VideosActivity.class))));
        addTile(Tiles.action(this, getString(R.string.all_apps), new Tiles.DotGrid(), w,
                v -> startActivity(new Intent(this, AppsActivity.class))));

        int content = dock.getChildCount() * (w + 2 * TILE_MARGIN) + 2 * (SHELF_PAD - TILE_MARGIN);
        shelf.getLayoutParams().width = dp(Math.min(content, screenDp - 2 * 48));
        shelf.requestLayout();

        int n = dock.getChildCount();
        Tiles.card(dock.getChildAt(Math.max(0, Math.min(focusIndex, n - 1)))).requestFocus();
    }

    private void addTile(View tile) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = lp.rightMargin = dp(TILE_MARGIN);
        dock.addView(tile, lp);
    }

    private int indexOfCard(View card) {
        for (int i = 0; i < dock.getChildCount(); i++)
            if (Tiles.card(dock.getChildAt(i)) == card) return i;
        return 0;
    }

    @Override public void onClick(Apps.App app) {
        try {
            startActivity(app.launch);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onOptions(final Apps.App app) {
        final List<String> ids = Apps.dock(this);
        final int pos = ids.indexOf(app.id);
        List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (pos > 0) {
            labels.add(getString(R.string.move_left));
            actions.add(() -> { ids.remove(pos); ids.add(pos - 1, app.id); saveAndFocus(ids, pos - 1); });
        }
        if (pos >= 0 && pos < ids.size() - 1) {
            labels.add(getString(R.string.move_right));
            actions.add(() -> { ids.remove(pos); ids.add(pos + 1, app.id); saveAndFocus(ids, pos + 1); });
        }
        labels.add(getString(R.string.remove_from_dock));
        actions.add(() -> { ids.remove(app.id); saveAndFocus(ids, Math.max(0, pos - 1)); });
        if (!Apps.SETTINGS_ID.equals(app.id)) {
            labels.add(getString(R.string.app_info));
            actions.add(() -> startActivity(Apps.appInfo(app.id)));
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void saveAndFocus(List<String> ids, int index) {
        Apps.saveDock(this, ids);
        rebuildDock();
        if (index < dock.getChildCount()) Tiles.card(dock.getChildAt(index)).requestFocus();
    }

    private void applyExtras(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("reset_dock", false)) { Apps.resetDock(this); dirty = true; }
        String csv = i.getStringExtra("dock");
        if (csv != null) { Apps.saveDock(this, csv); dirty = true; }
        int interval = i.getIntExtra("interval", -1);
        if (interval > 0) getSharedPreferences(Apps.PREFS, MODE_PRIVATE).edit()
                .putInt(Wallpapers.KEY_INTERVAL, interval).apply();
        if (i.getBooleanExtra("rescan", false)) wallpapers.rescan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyExtras(intent);
        // Pressing Home while already home: jump back to the first tile.
        if (!dirty && dock.getChildCount() > 0) {
            dockScroll.smoothScrollTo(0, 0);
            Tiles.card(dock.getChildAt(0)).requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (Apps.refreshIfStale(this)) dirty = true;
        if (dirty || !Apps.dock(this).toString().equals(shownDock))
            rebuildDock();
        wallpapers.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        wallpapers.stop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        wallpapers.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to.
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }
}
