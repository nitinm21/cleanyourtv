package io.github.gathin.frigatewall;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Full-screen, remote-friendly Frigate camera wall. Only fetches while this screen is visible. */
public final class WallActivity extends Activity {
    private static final int BG = Color.rgb(4, 6, 10);
    private static final int PANEL = Color.rgb(20, 25, 33);
    private static final int BLUE = Color.rgb(139, 199, 255);
    private static final String PREFS = "frigate_wall";
    private static final long SNAPSHOT_REFRESH_MS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Camera> cameras = new ArrayList<>();
    private final List<View> tiles = new ArrayList<>();
    private final List<ImageView> images = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final Map<String, String> liveStreams = new HashMap<>();
    private ScheduledExecutorService worker;
    private WallLiveVideo liveVideo;
    private String baseUrl = "";
    private String rtspHost = "";
    private String rtspUser = "";
    private String rtspPassword = "";
    private String expandedCamera;
    private ImageView expandedImage;
    private TextureView expandedVideo;
    private TextView expandedMode;
    private LinearLayout page;
    private GridLayout grid;
    private TextView status;
    private TextView refreshAction;
    private TextView settingsAction;
    private EditText urlEntry;
    private EditText rtspHostEntry;
    private EditText rtspUserEntry;
    private EditText rtspPasswordEntry;
    private boolean showingSetup;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        baseUrl = loadUrl();
        loadRtspSettings();
        if (baseUrl.isEmpty()) showSetup("");
        else fetchCameraList();
    }

    private String loadUrl() {
        File config = new File(getExternalFilesDir(null), "config.json");
        try (FileInputStream in = new FileInputStream(config)) {
            String value = new JSONObject(readText(in)).optString("frigateUrl", "").trim();
            if (!value.isEmpty()) return trimSlash(value);
        } catch (Exception ignored) { }
        return trimSlash(getSharedPreferences(PREFS, MODE_PRIVATE).getString("frigateUrl", ""));
    }

    private void loadRtspSettings() {
        String configuredHost = "";
        File config = new File(getExternalFilesDir(null), "config.json");
        try (FileInputStream in = new FileInputStream(config)) {
            configuredHost = new JSONObject(readText(in)).optString("rtspHost", "").trim();
        } catch (Exception ignored) { }
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        rtspHost = prefs.getString("rtspHost", configuredHost).trim();
        rtspUser = prefs.getString("rtspUser", "");
        rtspPassword = prefs.getString("rtspPassword", "");
    }

    private void saveRtspSettings() {
        rtspHost = rtspHostEntry.getText().toString().trim();
        rtspUser = rtspUserEntry.getText().toString().trim();
        rtspPassword = rtspPasswordEntry.getText().toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("rtspHost", rtspHost)
                .putString("rtspUser", rtspUser)
                .putString("rtspPassword", rtspPassword)
                .apply();
    }

    private void saveUrl(String value) {
        baseUrl = trimSlash(value);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("frigateUrl", baseUrl).apply();
    }

    private void persistConnectionConfig() {
        try {
            JSONObject config = readConfigFile();
            if (config == null) config = new JSONObject();
            config.put("frigateUrl", baseUrl);
            config.put("rtspHost", rtspHost);
            File file = new File(getExternalFilesDir(null), "config.json");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            android.util.Log.w("FrigateWall", "could not save local config", e);
        }
    }

    private String trimSlash(String value) {
        String s = value == null ? "" : value.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void showSetup(String error) {
        showingSetup = true;
        stopRefresh();
        stopLiveVideo();
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(72), dp(40), dp(72), dp(40));
        page.setBackgroundColor(BG);
        TextView title = text("Frigate Wall", 32, Color.WHITE, true);
        page.addView(title, matchWrap());
        TextView help = text("Enter your Frigate address to load the camera wall.", 18, 0xFF9DA8B6, false);
        page.addView(help, margins(matchWrap(), 0, dp(10), 0, dp(20)));
        urlEntry = new EditText(this);
        urlEntry.setSingleLine(true);
        urlEntry.setTextSize(20);
        urlEntry.setTextColor(Color.WHITE);
        urlEntry.setHintTextColor(0xFF8994A1);
        urlEntry.setHint("http://frigate-host:5000");
        urlEntry.setText(baseUrl);
        urlEntry.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        page.addView(urlEntry, margins(matchWrap(), 0, 0, 0, dp(16)));
        rtspHostEntry = new EditText(this);
        rtspHostEntry.setSingleLine(true);
        rtspHostEntry.setTextSize(18);
        rtspHostEntry.setTextColor(Color.WHITE);
        rtspHostEntry.setHintTextColor(0xFF8994A1);
        rtspHostEntry.setHint("RTSP host (blank uses the Frigate host)");
        rtspHostEntry.setText(rtspHost);
        rtspHostEntry.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        page.addView(rtspHostEntry, margins(matchWrap(), 0, 0, 0, dp(10)));
        rtspUserEntry = new EditText(this);
        rtspUserEntry.setSingleLine(true);
        rtspUserEntry.setTextSize(18);
        rtspUserEntry.setTextColor(Color.WHITE);
        rtspUserEntry.setHintTextColor(0xFF8994A1);
        rtspUserEntry.setHint("go2rtc RTSP username (optional)");
        rtspUserEntry.setText(rtspUser);
        page.addView(rtspUserEntry, margins(matchWrap(), 0, 0, 0, dp(10)));
        rtspPasswordEntry = new EditText(this);
        rtspPasswordEntry.setSingleLine(true);
        rtspPasswordEntry.setTextSize(18);
        rtspPasswordEntry.setTextColor(Color.WHITE);
        rtspPasswordEntry.setHintTextColor(0xFF8994A1);
        rtspPasswordEntry.setHint("go2rtc RTSP password (optional)");
        rtspPasswordEntry.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        rtspPasswordEntry.setText(rtspPassword);
        page.addView(rtspPasswordEntry, margins(matchWrap(), 0, 0, 0, dp(16)));
        Button connect = new Button(this);
        connect.setText("Save & refresh cameras");
        connect.setOnClickListener(v -> {
            String url = trimSlash(urlEntry.getText().toString());
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                showSetup("Enter an address starting with http:// or https://");
                return;
            }
            saveUrl(url);
            saveRtspSettings();
            persistConnectionConfig();
            fetchCameraList();
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(connect, new LinearLayout.LayoutParams(dp(300), dp(52)));
        if (!cameras.isEmpty()) {
            Button cancel = new Button(this);
            cancel.setText("Cancel");
            cancel.setOnClickListener(v -> returnToGrid());
            actions.addView(cancel, margins(new LinearLayout.LayoutParams(dp(180), dp(52)), dp(12), 0, 0, 0));
        }
        page.addView(actions, margins(matchWrap(), 0, 0, 0, dp(4)));
        if (!error.isEmpty()) page.addView(text(error, 18, 0xFFFF9B8A, false));
        setContentView(page);
    }

    private void returnToGrid() {
        expandedCamera = null;
        expandedImage = null;
        expandedVideo = null;
        stopLiveVideo();
        showGrid("Connected · " + cameras.size() + " cameras");
    }

    private void fetchCameraList() {
        stopRefresh();
        showLoading("Connecting to Frigate…");
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.execute(() -> {
            try {
                JSONObject root = getJson(baseUrl + "/api/config");
                JSONObject available = root.optJSONObject("cameras");
                if (available == null || available.length() == 0) throw new IllegalStateException("Frigate returned no cameras");
                Map<String, String> discoveredStreams = liveStreams(available);
                JSONObject config = readConfigFile();
                List<Camera> found = new ArrayList<>();
                JSONArray requested = config == null ? null : config.optJSONArray("cameras");
                if (requested != null && requested.length() > 0) {
                    java.util.HashSet<String> included = new java.util.HashSet<>();
                    for (int i = 0; i < requested.length(); i++) {
                        String id = requested.optString(i, "");
                        if (available.has(id) && !isReplayCamera(id) && included.add(id)) {
                            found.add(new Camera(id, pretty(id)));
                        }
                    }
                    if (config.optBoolean("newCameras", false)) {
                        List<String> extras = new ArrayList<>();
                        for (java.util.Iterator<String> it = available.keys(); it.hasNext();) {
                            String id = it.next();
                            if (!isReplayCamera(id) && included.add(id)) extras.add(id);
                        }
                        Collections.sort(extras);
                        for (String id : extras) found.add(new Camera(id, pretty(id)));
                    }
                } else {
                    List<String> ids = new ArrayList<>();
                    for (java.util.Iterator<String> it = available.keys(); it.hasNext();) {
                        String id = it.next();
                        if (!isReplayCamera(id)) ids.add(id);
                    }
                    Collections.sort(ids);
                    for (String id : ids) found.add(new Camera(id, pretty(id)));
                }
                if (found.isEmpty()) throw new IllegalStateException("No configured cameras matched Frigate");
                cameras.clear();
                cameras.addAll(found);
                liveStreams.clear();
                liveStreams.putAll(discoveredStreams);
                main.post(() -> showGrid("Connected · " + cameras.size() + " cameras"));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Could not connect to Frigate" : e.getMessage();
                main.post(() -> showSetup(message));
            }
        });
    }

    /** Match Frigate Glance: replay cameras are debug copies, not separate cameras to show. */
    private static boolean isReplayCamera(String id) {
        return id.startsWith("_replay_");
    }

    private JSONObject readConfigFile() {
        File f = new File(getExternalFilesDir(null), "config.json");
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(readText(in));
        } catch (Exception ignored) { return null; }
    }

    private void showLoading(String message) {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(BG);
        page.addView(text("Frigate Wall", 32, Color.WHITE, true));
        status = text(message, 20, 0xFF9DA8B6, false);
        page.addView(status);
        setContentView(page);
    }

    private void showGrid(String message) {
        showingSetup = false;
        expandedMode = null;
        images.clear();
        labels.clear();
        tiles.clear();
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(26), dp(16), dp(26), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text("FRIGATE  /  CAMERA WALL", 18, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1));
        status = text(message, 14, 0xFF9DA8B6, false);
        header.addView(status);
        settingsAction = actionButton("Settings", () -> showSetup(""));
        refreshAction = actionButton("Refresh", this::fetchCameraList);
        settingsAction.setNextFocusRightId(refreshAction.getId());
        refreshAction.setNextFocusLeftId(settingsAction.getId());
        header.addView(settingsAction, margins(new LinearLayout.LayoutParams(-2, dp(38)), dp(12), 0, 0, 0));
        header.addView(refreshAction, margins(new LinearLayout.LayoutParams(-2, dp(38)), dp(8), 0, 0, 0));
        page.addView(header, matchWrap());

        grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setRowCount((cameras.size() + 2) / 3);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, 0, 1);
        page.addView(grid, gridParams);

        for (int i = 0; i < cameras.size(); i++) addTile(i);
        page.addView(text("OK  Open camera     BACK  Exit", 13, 0xFF778392, false));
        setContentView(page);
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
        beginRefresh();
    }

    private void addTile(int index) {
        Camera camera = cameras.get(index);
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        if (index == 0 && refreshAction != null) tile.setNextFocusUpId(refreshAction.getId());
        tile.setClipToOutline(true);
        tile.setBackground(tileBackground(false));
        tile.setElevation(dp(2));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(PANEL);
        tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView label = text(camera.label, 16, Color.WHITE, true);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        label.setPadding(dp(14), 0, dp(12), 0);
        label.setBackgroundColor(0xC9000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM);
        tile.addView(label, lp);
        tile.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(tileBackground(focused));
            v.setScaleX(focused ? 1.025f : 1f);
            v.setScaleY(focused ? 1.025f : 1f);
            v.setElevation(focused ? dp(10) : dp(2));
        });
        tile.setOnClickListener(v -> showSingle(camera));

        int row = index / 3, col = index % 3;
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row, 1f), GridLayout.spec(col, 1f));
        params.width = 0;
        params.height = 0;
        params.setMargins(dp(7), dp(7), dp(7), dp(7));
        grid.addView(tile, params);
        tiles.add(tile);
        images.add(image);
        labels.add(label);
    }

    private GradientDrawable tileBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(PANEL);
        d.setCornerRadius(dp(12));
        d.setStroke(focused ? dp(3) : dp(1), focused ? BLUE : 0xFF26313E);
        return d;
    }

    private TextView actionButton(String label, Runnable action) {
        TextView button = text(label, 14, Color.WHITE, true);
        button.setFocusable(true);
        button.setClickable(true);
        button.setId(View.generateViewId());
        button.setPadding(dp(14), 0, dp(14), 0);
        styleActionButton(button, false);
        button.setOnFocusChangeListener((view, focused) -> styleActionButton(button, focused));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void styleActionButton(TextView button, boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(focused ? BLUE : PANEL);
        background.setStroke(dp(focused ? 2 : 1), focused ? Color.WHITE : 0xFF26313E);
        button.setBackground(background);
        button.setTextColor(focused ? Color.rgb(4, 6, 10) : Color.WHITE);
        button.setElevation(focused ? dp(5) : 0);
    }

    private void showSingle(Camera camera) {
        expandedCamera = camera.id;
        stopRefresh();
        stopLiveVideo();
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(28), dp(18), dp(28), dp(18));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(camera.label, 22, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1));
        expandedMode = text("CONNECTING", 13, 0xFF9DA8B6, true);
        header.addView(expandedMode, wrap());
        page.addView(header, matchWrap());
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        expandedImage = image;
        TextureView video = new TextureView(this);
        video.setAlpha(0f);
        expandedVideo = video;
        FrameLayout picture = new FrameLayout(this);
        picture.addView(image, new FrameLayout.LayoutParams(-1, -1));
        picture.addView(video, new FrameLayout.LayoutParams(-1, -1));
        page.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(text("BACK  Return to all cameras", 14, 0xFF778392, false));
        setContentView(page);
        startSinglePlayback(camera);
    }

    private void refreshSingle(Camera camera, ImageView target) {
        Bitmap bitmap = loadSnapshot(camera.id);
        if (bitmap != null) main.post(() -> {
            if (expandedCamera != null && expandedCamera.equals(camera.id)) target.setImageBitmap(bitmap);
        });
    }

    private void startSinglePlayback(Camera camera) {
        if (expandedImage == null || expandedVideo == null || !camera.id.equals(expandedCamera)) return;
        expandedVideo.animate().cancel();
        expandedVideo.setAlpha(0f);
        setExpandedMode("CONNECTING", 0xFF9DA8B6);
        startSingleSnapshots(camera);
        String url = liveUrl(camera.id);
        if (url == null) {
            setExpandedMode("SNAPSHOTS", 0xFF9DA8B6);
            return;
        }
        liveVideo = new WallLiveVideo(this, new WallLiveVideo.Listener() {
            @Override public void onPlaying() {
                if (!camera.id.equals(expandedCamera) || expandedVideo == null) return;
                expandedVideo.animate().alpha(1f).setDuration(250).start();
                setExpandedMode("LIVE", 0xFFFF6B6B);
                stopRefresh();
            }

            @Override public void onGaveUp() {
                if (!camera.id.equals(expandedCamera) || expandedVideo == null) return;
                expandedVideo.setAlpha(0f);
                setExpandedMode("SNAPSHOTS", 0xFF9DA8B6);
                startSingleSnapshots(camera);
            }
        });
        liveVideo.start(url, expandedVideo);
    }

    private void startSingleSnapshots(Camera camera) {
        if (expandedImage == null || !camera.id.equals(expandedCamera)) return;
        stopRefresh();
        ImageView target = expandedImage;
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.execute(() -> refreshSingle(camera, target));
        worker.scheduleWithFixedDelay(() -> refreshSingle(camera, target), SNAPSHOT_REFRESH_MS,
                SNAPSHOT_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    private void setExpandedMode(String label, int color) {
        TextView mode = expandedMode;
        if (mode != null) main.post(() -> {
            if (mode == expandedMode) {
                mode.setText(label);
                mode.setTextColor(color);
            }
        });
    }

    private String liveUrl(String camera) {
        String host = rtspHost.isEmpty() ? Uri.parse(baseUrl).getHost() : rtspHost;
        if (host == null || host.isEmpty()) return null;
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        String stream = liveStreams.containsKey(camera) ? liveStreams.get(camera) : camera;
        return WallLiveVideo.rtspUrl(host, rtspUser, rtspPassword, stream);
    }

    /** Frigate's configured live stream alias, or the camera id when no alias is declared. */
    private static Map<String, String> liveStreams(JSONObject cameras) {
        Map<String, String> out = new HashMap<>();
        for (java.util.Iterator<String> it = cameras.keys(); it.hasNext();) {
            String name = it.next();
            JSONObject camera = cameras.optJSONObject(name);
            JSONObject live = camera == null ? null : camera.optJSONObject("live");
            JSONObject streams = live == null ? null : live.optJSONObject("streams");
            String stream = name;
            if (streams != null) {
                java.util.Iterator<String> keys = streams.keys();
                if (keys.hasNext()) stream = streams.optString(keys.next(), name);
            }
            out.put(name, stream);
        }
        return out;
    }

    private void stopLiveVideo() {
        if (liveVideo != null) {
            liveVideo.release();
            liveVideo = null;
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && showingSetup && !cameras.isEmpty()) {
            returnToGrid();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && expandedCamera != null) {
            expandedCamera = null;
            expandedImage = null;
            expandedVideo = null;
            stopRefresh();
            stopLiveVideo();
            showGrid("Connected · " + cameras.size() + " cameras");
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) && !baseUrl.isEmpty()) {
            showSetup("");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void beginRefresh() {
        stopRefresh();
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.execute(this::refreshAll);
        worker.scheduleWithFixedDelay(this::refreshAll, 5, 5, TimeUnit.SECONDS);
    }

    private void refreshAll() {
        long time = System.currentTimeMillis();
        for (int i = 0; i < cameras.size(); i++) {
            final int index = i;
            Bitmap bitmap = loadSnapshot(cameras.get(i).id);
            if (bitmap != null) main.post(() -> {
                if (index < images.size()) images.get(index).setImageBitmap(bitmap);
                if (status != null) status.setText(cameras.size() + " cameras · " +
                        new java.text.SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new java.util.Date(time)));
            });
        }
    }

    private Bitmap loadSnapshot(String camera) {
        HttpURLConnection c = null;
        try {
            String encoded = URLEncoder.encode(camera, StandardCharsets.UTF_8.name());
            c = (HttpURLConnection) new URL(baseUrl + "/api/" + encoded + "/latest.jpg?h=360&t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1 && out.size() < 2_000_000) out.write(buf, 0, n);
                byte[] bytes = out.toByteArray();
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (c.getResponseCode() != 200) throw new IllegalStateException("Frigate returned HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }

    private String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private String pretty(String id) {
        String s = id.replace('_', ' ');
        if (s.endsWith(" camera")) s = s.substring(0, s.length() - 7);
        String[] words = s.split(" ");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    private void stopRefresh() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    @Override public void onPause() {
        stopRefresh();
        stopLiveVideo();
        super.onPause();
    }

    @Override public void onResume() {
        super.onResume();
        if (expandedCamera != null && expandedImage != null && worker == null) {
            Camera camera = camera(expandedCamera);
            if (camera != null) startSinglePlayback(camera);
        } else if (expandedCamera == null && !cameras.isEmpty() && worker == null) {
            beginRefresh();
        }
    }

    @Override public void onDestroy() {
        stopRefresh();
        stopLiveVideo();
        super.onDestroy();
    }

    private Camera camera(String id) {
        for (Camera c : cameras) if (c.id.equals(id)) return c;
        return null;
    }

    private static final class Camera {
        final String id;
        final String label;
        Camera(String id, String label) { this.id = id; this.label = label; }
    }
}
