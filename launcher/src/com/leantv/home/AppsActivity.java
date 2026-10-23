package com.leantv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Every launchable app in a grid. Long-press (or Menu) a tile to add it to / remove it from the dock. */
public class AppsActivity extends Activity implements Tiles.Listener {

    private static final int COLUMNS = 5;
    private LinearLayout grid;

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

        TextView title = new TextView(this);
        title.setText(R.string.apps_title);
        Fonts.style(title, Fonts.SEMIBOLD, 26, 0.95f);
        title.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.bottomMargin = dp(8);
        content.addView(title, tlp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        content.addView(grid);
        scroll.addView(content);
        setContentView(scroll);
        build();
    }

    private void build() {
        grid.removeAllViews();
        LinearLayout row = null;
        int i = 0;
        int w = Math.min(150, (getResources().getConfiguration().screenWidthDp - 128) / COLUMNS - 32);
        for (Apps.App a : Apps.all(this).values()) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setGravity(Gravity.START);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.topMargin = dp(14);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(32);
            row.addView(Tiles.make(this, a, w, this), lp);
            i++;
        }
        if (grid.getChildCount() > 0) {
            Tiles.card(((LinearLayout) grid.getChildAt(0)).getChildAt(0)).requestFocus();
        }
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
        final boolean inDock = ids.contains(app.id);
        CharSequence[] items = Apps.SETTINGS_ID.equals(app.id)
                ? new CharSequence[]{getString(inDock ? R.string.remove_from_dock : R.string.add_to_dock)}
                : new CharSequence[]{getString(inDock ? R.string.remove_from_dock : R.string.add_to_dock),
                                     getString(R.string.app_info)};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items, (d, which) -> {
                    if (which == 1) { startActivity(Apps.appInfo(app.id)); return; }
                    if (inDock) ids.remove(app.id); else ids.add(app.id);
                    Apps.saveDock(this, ids);
                    Toast.makeText(this, getString(inDock ? R.string.remove_from_dock : R.string.add_to_dock)
                            + ": " + app.label, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }
}
