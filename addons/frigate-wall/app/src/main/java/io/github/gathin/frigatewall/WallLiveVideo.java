package io.github.gathin.frigatewall;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** One muted go2rtc RTSP stream for the enlarged camera; callers fall back to snapshots on failure. */
@OptIn(markerClass = UnstableApi.class)
final class WallLiveVideo {
    interface Listener {
        void onPlaying();
        void onGaveUp();
    }

    static final long GIVE_UP_MS = 4000;
    static final int RTSP_PORT = 8554;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean playing;
    private long startedAt;
    private final Runnable giveUp = this::fail;

    WallLiveVideo(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    static String rtspUrl(String host, String username, String password, String stream) {
        String auth = username == null || username.isEmpty() ? "" : encode(username)
                + (password == null || password.isEmpty() ? "" : ":" + encode(password)) + "@";
        return "rtsp://" + auth + host + ":" + RTSP_PORT + "/" + encode(stream);
    }

    void start(String url, TextureView surface) {
        release();
        playing = false;
        startedAt = android.os.SystemClock.elapsedRealtime();
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 500, 500)
                .build();
        player = new ExoPlayer.Builder(context).setLoadControl(load).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        player.setVideoTextureView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (playing) return;
                playing = true;
                main.removeCallbacks(giveUp);
                Log.i("FrigateWall", "live video started after "
                        + (android.os.SystemClock.elapsedRealtime() - startedAt) + " ms");
                listener.onPlaying();
            }

            @Override public void onPlayerError(PlaybackException error) {
                Log.w("FrigateWall", "live video: " + error.getErrorCodeName());
                fail();
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
        main.postDelayed(giveUp, GIVE_UP_MS);
    }

    void release() {
        main.removeCallbacks(giveUp);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void fail() {
        boolean wasActive = player != null;
        release();
        if (wasActive) listener.onGaveUp();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
