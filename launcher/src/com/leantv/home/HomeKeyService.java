package com.leantv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the Home button open LeanTV Home on boxes whose firmware won't let another launcher outrank the
 * stock one (and blocks disabling it). Off unless switched on with adb; restore.ps1 switches it off.
 *
 *   settings put secure enabled_accessibility_services com.leantv.home/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 *
 * The firmware handles the Home key before accessibility services see it, so instead whenever the stock
 * launcher comes to the front (Home button, boot, an app finishing) it is replaced by LeanTV Home.
 * If LeanTV Home keeps failing to stay up, the stock launcher is left alone so the TV stays usable.
 */
public class HomeKeyService extends AccessibilityService {

    static final String STOCK = "com.google.android.tvlauncher";
    private static final int MAX_REDIRECTS = 6;
    private static final long WINDOW_MS = 10000;
    /** At boot the stock launcher can be up before this service is; take over if we connect this early. */
    private static final long BOOT_MS = 5 * 60 * 1000;

    private final long[] recent = new long[MAX_REDIRECTS];
    private int next;

    @Override
    protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() < BOOT_MS) openHome();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && e.getPackageName() != null && STOCK.contentEquals(e.getPackageName())) {
            openHome();
        }
    }

    private void openHome() {
        // Many redirects in a short time means LeanTV Home is crashing: stop fighting the stock launcher.
        long now = SystemClock.elapsedRealtime(), oldest = recent[next];
        if (oldest != 0 && now - oldest < WINDOW_MS) return;
        recent[next] = now;
        next = (next + 1) % MAX_REDIRECTS;
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
