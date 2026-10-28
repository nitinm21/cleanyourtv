package io.github.quicodomingo.continuewatching;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole add-on: Stremio's Continue watching (in progress, next and new episodes) and the next scheduled
 * episode of each show you follow. Until Stremio is connected it shows the QR login instead.
 *
 * Stremio is read only while this screen is open: on open, and again on return when the last read is older
 * than {@link #REFRESH_MS}. The last lists are kept, so the page shows at once and offline.
 */
public class WatchingActivity extends Activity {

    private static final long MINUTE_MS = 60_000L;
    private static final long REFRESH_MS = 30_000L;
    /** How long a login code waits for the phone. */
    private static final long LOGIN_TIMEOUT_MS = 10 * MINUTE_MS;
    private static final long LOGIN_POLL_MS = 3_000L;
    private static final float FOCUS_SCALE = 1.06f;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private View page;
    private View login;
    private LinearLayout cards;
    private LinearLayout upcoming;
    private TextView empty;
    private TextView upcomingHeading;
    private ImageView qr;
    private TextView link;
    private TextView status;

    private long lastFetch;
    /** Bumped for every new login code and whenever the login stops; a poll whose number is stale ends. */
    private int loginRun;
    /** What the page shows now, so a refresh with nothing new leaves focus where it is. */
    private String shown = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watching);
        page = findViewById(R.id.page);
        login = findViewById(R.id.login);
        cards = findViewById(R.id.cards);
        upcoming = findViewById(R.id.upcoming);
        empty = findViewById(R.id.empty);
        upcomingHeading = findViewById(R.id.upcomingHeading);
        qr = findViewById(R.id.qr);
        link = findViewById(R.id.link);
        status = findViewById(R.id.status);
        login.setOnClickListener(v -> startLogin());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (StremioApi.authKey(this) == null) {
            startLogin();
        } else {
            showPage(StremioApi.cached(this), StremioApi.cachedUpcoming(this));
            refresh(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        loginRun++;   // stop waiting for the phone while another app is in front
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- the page --------------------------------------------------------------------------------

    private void refresh(boolean force) {
        String key = StremioApi.authKey(this);
        long now = SystemClock.elapsedRealtime();
        if (key == null || (!force && lastFetch != 0 && now - lastFetch < REFRESH_MS)) return;
        lastFetch = now;
        io.execute(() -> {
            try {
                StremioApi.Library lib = StremioApi.fetch(key, true, true);
                StremioApi.cache(this, lib.watching);
                StremioApi.cacheUpcoming(this, lib.upcoming);
                main.post(() -> {
                    if (!isFinishing() && StremioApi.authKey(this) != null) showPage(lib.watching, lib.upcoming);
                });
            } catch (StremioApi.ApiException e) {
                if (e.code == StremioApi.ERR_SESSION) main.post(this::disconnect);
            } catch (Exception ignored) {
                // Offline or Stremio down: the page keeps the last lists.
            }
        });
    }

    private void showPage(List<StremioApi.Item> watching, List<StremioApi.Upcoming> next) {
        login.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
        String signature = signature(watching, next);
        if (signature.equals(shown)) return;
        shown = signature;

        // Put focus back on the same card or row after the rebuild.
        View focused = getCurrentFocus();
        int focusedCard = focused != null && focused.getParent() instanceof View
                ? cards.indexOfChild((View) focused.getParent()) : -1;
        int focusedRow = focused != null ? upcoming.indexOfChild(focused) : -1;

        cards.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        for (StremioApi.Item it : watching) cards.addView(card(inf, it));
        empty.setVisibility(watching.isEmpty() ? View.VISIBLE : View.GONE);

        upcoming.removeAllViews();
        for (StremioApi.Upcoming u : next) upcoming.addView(row(inf, u));
        upcomingHeading.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);

        if (focusedRow >= 0 && upcoming.getChildCount() > 0) {
            upcoming.getChildAt(Math.min(focusedRow, upcoming.getChildCount() - 1)).requestFocus();
        } else if (cards.getChildCount() > 0) {
            int i = Math.max(0, Math.min(focusedCard, cards.getChildCount() - 1));
            cards.getChildAt(i).findViewById(R.id.card).requestFocus();
        } else if (upcoming.getChildCount() > 0) {
            upcoming.getChildAt(0).requestFocus();
        } else {
            findViewById(R.id.disconnect).requestFocus();
        }
    }

    private static String signature(List<StremioApi.Item> watching, List<StremioApi.Upcoming> next) {
        StringBuilder sb = new StringBuilder();
        for (StremioApi.Item it : watching) {
            sb.append(it.id).append(it.videoId).append(it.kind).append(it.timeOffset / MINUTE_MS).append('|');
        }
        sb.append('#');
        for (StremioApi.Upcoming u : next) sb.append(u.showId).append(u.season).append(':').append(u.episode)
                .append(u.released).append('|');
        return sb.toString();
    }

    private View card(LayoutInflater inf, StremioApi.Item it) {
        View v = inf.inflate(R.layout.item_card, cards, false);
        View card = v.findViewById(R.id.card);
        ImageView image = v.findViewById(R.id.image);
        TextView badge = v.findViewById(R.id.badge);
        TextView title = v.findViewById(R.id.title);
        TextView subtitle = v.findViewById(R.id.subtitle);

        title.setText(it.name);
        String details = details(it);
        subtitle.setText(details);
        subtitle.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        card.setClipToOutline(true);
        ImageLoader.load(image, it.imageUrl(), image.getLayoutParams().width);

        boolean inProgress = it.kind == StremioApi.KIND_PROGRESS && it.duration > 0;
        v.findViewById(R.id.track).setVisibility(inProgress ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.shade).setVisibility(inProgress ? View.VISIBLE : View.GONE);
        if (inProgress) {
            View bar = v.findViewById(R.id.bar);
            bar.setPivotX(0f);
            bar.setScaleX(Math.max(0.03f, Math.min(1f, (float) it.timeOffset / it.duration)));
        }
        if (it.kind != StremioApi.KIND_PROGRESS) {
            // A new episode stands out in white; the next one is a quiet dark badge.
            boolean isNew = it.kind == StremioApi.KIND_NEW;
            badge.setText(isNew ? R.string.badge_new : R.string.badge_next);
            badge.getBackground().mutate().setTint(isNew ? 0xFFFFFFFF : 0xB3000000);
            badge.setTextColor(isNew ? 0xFF111111 : 0xFFFFFFFF);
            badge.setVisibility(View.VISIBLE);
        }

        card.setOnClickListener(x -> open(it.deepLink()));
        card.setOnFocusChangeListener((x, focused) -> {
            float s = focused ? FOCUS_SCALE : 1f;
            x.animate().scaleX(s).scaleY(s).setDuration(focused ? 200 : 160)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            title.animate().alpha(focused ? 1f : 0.7f).setDuration(160).start();
        });
        return v;
    }

    /** "S2 · E6 · 40 min left" for something in progress, "S2 · E7 · Episode name" for a next or new one. */
    private String details(StremioApi.Item it) {
        List<String> parts = new ArrayList<>();
        int[] se = it.seasonEpisode();
        if (se != null) parts.add(getString(R.string.season_episode, se[0], se[1]));
        if (it.kind != StremioApi.KIND_PROGRESS) {
            if (it.episodeName != null) parts.add(it.episodeName);
        } else if (it.duration > 0) {
            long left = Math.max(1, (it.duration - it.timeOffset) / MINUTE_MS);
            parts.add(getString(R.string.time_left, (int) left));
        }
        return String.join(" · ", parts);
    }

    private View row(LayoutInflater inf, StremioApi.Upcoming u) {
        View v = inf.inflate(R.layout.item_upcoming, upcoming, false);
        ((TextView) v.findViewById(R.id.date)).setText(dateLabel(this, u.released));
        ((TextView) v.findViewById(R.id.show)).setText(u.showName);
        String ep = getString(R.string.season_episode, u.season, u.episode);
        ((TextView) v.findViewById(R.id.episode)).setText(Episodes.hasRealName(u.episodeName)
                ? ep + " · " + u.episodeName : ep);
        v.setOnClickListener(x -> open(u.deepLink()));
        return v;
    }

    /**
     * "Today", "Tomorrow", else a short weekday and date in the TV's language (e.g. "Fri, Oct 23"), with the
     * year when it isn't this one.
     */
    private static String dateLabel(Context ctx, long when) {
        LocalDate day = Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return ctx.getString(R.string.today);
        if (day.equals(today.plusDays(1))) return ctx.getString(R.string.tomorrow);
        Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
        String skeleton = day.getYear() == today.getYear() ? "EEEdMMM" : "EEEdMMMy";
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton);
        return new SimpleDateFormat(pattern, loc).format(new Date(when));
    }

    private void open(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_stremio, Toast.LENGTH_LONG).show();
        }
    }

    private void disconnect() {
        StremioApi.setAuthKey(this, null);
        shown = "";
        lastFetch = 0;
        cards.removeAllViews();
        upcoming.removeAllViews();
        startLogin();
    }

    // ---- QR login --------------------------------------------------------------------------------

    /** Gets a link code and waits for the phone to confirm it at link.stremio.com. */
    private void startLogin() {
        int run = ++loginRun;
        page.setVisibility(View.GONE);
        login.setVisibility(View.VISIBLE);
        login.requestFocus();
        status.setText(R.string.stremio_waiting);
        link.setText("");
        qr.setImageDrawable(null);
        io.execute(() -> {
            StremioApi.LinkCode code;
            try {
                code = StremioApi.createLink();
            } catch (Exception e) {
                main.post(() -> {
                    if (run == loginRun) status.setText(R.string.stremio_unreachable);
                });
                return;
            }
            main.post(() -> {
                if (run != loginRun) return;
                link.setText(code.link.replaceFirst("^https?://", ""));
                ImageLoader.load(qr, code.qrUrl, qr.getLayoutParams().width);
            });
            long deadline = SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS;
            while (run == loginRun && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(LOGIN_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                String key;
                try {
                    key = StremioApi.readLink(code.code);
                } catch (Exception e) {
                    continue;   // a network blip: keep waiting
                }
                if (key != null) {
                    main.post(() -> {
                        if (run != loginRun) return;
                        loginRun++;
                        StremioApi.setAuthKey(this, key);
                        showPage(new ArrayList<>(), new ArrayList<>());
                        refresh(true);
                    });
                    return;
                }
            }
            main.post(() -> {
                if (run == loginRun) status.setText(R.string.stremio_expired);
            });
        });
    }
}
