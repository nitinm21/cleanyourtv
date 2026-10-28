package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Glass-TV-inspired scenic images with title/description from Bing's public daily photo archive.
 * No API key. Does not touch launcher/Home or Netflix settings. */
final class PlacesShelf {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long ROTATE_MS = 20000L;
    private static final long ARCHIVE_TTL_MS = 86400000L;
    private static JSONArray images;
    private static long loadedAt;
    private static int cursor;
    private static int generation;
    private static float descriptionAlpha = 1f;
    private static Runnable scheduled;
    private PlacesShelf() {}

    static void refresh(Activity activity) {
        if (!activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE)
                .getBoolean("places_wallpaper_enabled", true)) {
            pause(activity);
            return;
        }
        generation++;
        int token = generation;
        if (scheduled != null) UI.removeCallbacks(scheduled);
        ImageView picture = activity.findViewById(R.id.netflixWallpaper);
        TextView title = activity.findViewById(R.id.netflixWallpaperTitle);
        TextView desc = activity.findViewById(R.id.placesDescription);
        if (picture == null || title == null || desc == null) return;
        // Show the previous photo immediately if the cached archive is still available.
        if (images != null && images.length() > 0) show(activity, token);
        if (images != null && System.currentTimeMillis() - loadedAt < ARCHIVE_TTL_MS) return;
        IO.execute(() -> {
            try {
                String json = fetchText("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US");
                JSONArray found = new JSONObject(json).getJSONArray("images");
                if (found.length() == 0) return;
                UI.post(() -> {
                    if (generation != token || activity.isDestroyed() || activity.isFinishing()) return;
                    images = found; loadedAt = System.currentTimeMillis(); cursor = 0;
                    if (scheduled != null) UI.removeCallbacks(scheduled);
                    show(activity, token);
                });
            } catch (Exception e) {
                android.util.Log.w("PlacesShelf", "Scene feed unavailable", e);
                UI.post(() -> {
                    if (generation == token) {
                        title.setText("PLACES TO VISIT"); title.setVisibility(View.VISIBLE);
                        desc.setText("Scenic wallpapers unavailable. Check Internet connection.");
                        desc.setAlpha(descriptionAlpha);
                        desc.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }
    private static void show(Activity activity, int token) {
        if (generation != token || images == null || images.length() == 0) return;
        JSONObject scene = images.optJSONObject(cursor++ % images.length());
        if (scene == null) return;
        String relative = scene.optString("url", "");
        String url = relative.startsWith("http") ? relative : "https://www.bing.com" + relative;
        String copyright = scene.optString("copyright", "");
        String name = scene.optString("title", "PLACES TO VISIT");
        IO.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = fetchBytes(url);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                int sample = 1;
                while (options.outWidth/sample > 1600 || options.outHeight/sample > 900) sample *= 2;
                options.inJustDecodeBounds = false;
                options.inSampleSize = sample;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            } catch (Exception e) { android.util.Log.w("PlacesShelf", "Photo download failed", e); }
            final Bitmap loaded = bitmap;
            UI.post(() -> {
                if (generation != token || activity.isDestroyed() || activity.isFinishing()) {
                    if (loaded != null) loaded.recycle(); return;
                }
                ImageView picture = activity.findViewById(R.id.netflixWallpaper);
                View shade = activity.findViewById(R.id.netflixWallpaperShade);
                TextView title = activity.findViewById(R.id.netflixWallpaperTitle);
                TextView desc = activity.findViewById(R.id.placesDescription);
                if (loaded != null && picture != null) {
                    picture.animate().cancel(); picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    picture.setImageBitmap(loaded); picture.setVisibility(View.VISIBLE);
                    picture.setAlpha(0f); picture.animate().alpha(0.8f).setDuration(900).start();
                }
                if (shade != null) { shade.setAlpha(1f); shade.setVisibility(View.VISIBLE); }
                if (title != null) { title.setText(name.toUpperCase(java.util.Locale.ROOT)); title.setVisibility(View.VISIBLE); }
                if (desc != null) {
                    desc.setText(copyright); desc.setAlpha(descriptionAlpha);
                    desc.setVisibility(View.VISIBLE);
                }
                scheduled = () -> show(activity, token);
                UI.postDelayed(scheduled, ROTATE_MS);
            });
        });
    }
    static void onScrolled(Activity activity, float translationY) {
        descriptionAlpha = Math.max(0f, 1f + translationY
                / (48f * activity.getResources().getDisplayMetrics().density));
        View desc = activity.findViewById(R.id.placesDescription);
        if (desc != null) { desc.setTranslationY(0); desc.setAlpha(descriptionAlpha); }
    }
    static void pause(Activity activity) {
        generation++;
        if (scheduled != null) UI.removeCallbacks(scheduled);
        View desc = activity.findViewById(R.id.placesDescription);
        if (desc != null) desc.setVisibility(View.GONE);
    }
    private static String fetchText(String url) throws Exception {
        return new String(fetchBytes(url), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(8000); conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; PlacesShelf)");
        try(InputStream in=conn.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n; int size=0;
            while ((n=in.read(b))!=-1) { size+=n; if (size>5000000) throw new IllegalStateException("Picture too large"); out.write(b,0,n); }
            return out.toByteArray();
        } finally { conn.disconnect(); }
    }
}
