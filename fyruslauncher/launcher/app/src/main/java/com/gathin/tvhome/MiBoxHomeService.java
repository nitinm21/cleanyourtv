package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Opt-in Home redirect for Mi Box firmware that protects the system Home components. */
public class MiBoxHomeService extends AccessibilityService {
    private long lastLaunch;

    private void openHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 750) return;
        lastLaunch = now;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(event.getPackageName());
        String cls = String.valueOf(event.getClassName());
        if ((pkg.equals("com.google.android.apps.tv.launcherx")
                && cls.equals("com.google.android.apps.tv.launcherx.home.HomeActivity"))
                || (pkg.equals("com.google.android.tungsten.setupwraith")
                && cls.equals("com.google.android.tungsten.setupwraith.RecoveryActivity"))) {
            openHome();
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME
                && event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    @Override
    public void onInterrupt() { }
}
