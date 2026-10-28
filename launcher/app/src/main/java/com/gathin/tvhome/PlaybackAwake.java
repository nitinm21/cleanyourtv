package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.List;

/**
 * stay_awake_while_playing: while another app is playing music or video, keep the screen from
 * sleeping. The sleep timer and screensaver run again as soon as playback pauses or stops.
 * Registered once from Home; the callback stays with the process, so it still applies after Home
 * is in the background.
 */
final class PlaybackAwake {
    private static final long POKE_MS = 20_000L;

    private static Context app;
    private static AudioManager.AudioPlaybackCallback callback;
    private static PowerManager.WakeLock lock;
    private static Handler handler;
    private static final Runnable poke = new Runnable() {
        @Override public void run() {
            if (lock == null || !lock.isHeld() || app == null) return;
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            try {
                // Public on the device, missing from some compile stubs.
                pm.getClass().getMethod("userActivity", long.class, boolean.class)
                        .invoke(pm, SystemClock.uptimeMillis(), false);
            } catch (ReflectiveOperationException ignored) {
                // Some builds only allow this from the system. The screen wake lock still holds.
            }
            handler.postDelayed(this, POKE_MS);
        }
    };

    private PlaybackAwake() {}

    static void start(Context ctx, boolean enabled) {
        app = ctx.getApplicationContext();
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (!enabled) {
            release();
            return;
        }
        if (callback != null) return;
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "tvhome:playback");
        lock.setReferenceCounted(false);
        callback = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                if (mediaPlaying(configs)) hold();
                else release();
            }
        };
        audio.registerAudioPlaybackCallback(callback, handler);
        handler.post(() -> {
            if (mediaPlaying(audio.getActivePlaybackConfigurations())) hold();
        });
    }

    /**
     * True while music or video is actually started. This TV does not tell a normal app which
     * package owns the player, but the player is listed only while it is playing and drops out
     * as soon as it pauses. Off is shown from that.
     */
    static boolean isPackagePlaying(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        List<AudioPlaybackConfiguration> configs = audio.getActivePlaybackConfigurations();
        if (configs == null) return false;
        for (AudioPlaybackConfiguration c : configs) {
            if (playerState(c) != 2) continue;
            AudioAttributes a = c.getAudioAttributes();
            if (a != null && a.getUsage() == AudioAttributes.USAGE_MEDIA) return true;
        }
        return false;
    }

    private static boolean mediaPlaying(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) return false;
        for (AudioPlaybackConfiguration c : configs) {
            if (playerState(c) != 2) continue; // PLAYER_STATE_STARTED
            AudioAttributes a = c.getAudioAttributes();
            if (a != null && a.getUsage() == AudioAttributes.USAGE_MEDIA) return true;
        }
        return false;
    }

    private static int playerState(AudioPlaybackConfiguration c) {
        try {
            return (Integer) AudioPlaybackConfiguration.class.getMethod("getPlayerState").invoke(c);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static void hold() {
        if (lock != null && !lock.isHeld()) lock.acquire();
        handler.removeCallbacks(poke);
        handler.post(poke);
    }

    private static void release() {
        if (handler != null) handler.removeCallbacks(poke);
        if (lock != null && lock.isHeld()) lock.release();
    }
}
