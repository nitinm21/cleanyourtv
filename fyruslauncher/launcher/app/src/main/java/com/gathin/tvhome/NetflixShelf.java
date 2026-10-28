package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional Netflix Malaysia wallpaper slideshow. No focus targets, clicks, or Netflix history access. */
final class NetflixShelf {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final String ENDPOINT = "https://ottasia.com/api/mcp/whats-new?provider=netflix&country=";
    private static String loadedCountry = "";
    private static final long INTERVAL = 12000L;
    private static final int posterCount = 7;
    private static final long CACHE_TTL = 86400000L;
    private static boolean fetching;
    private static JSONArray catalog;
    private static long lastFetch;
    private static int index;
    private static int generation;
    private static Runnable next;
    private static boolean active;
    private NetflixShelf() {}

    static void attach(Activity a) {
        hide(a);
    }
    static void refresh(Activity a) {
        SharedPreferences preferences = a.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        active = preferences.getBoolean("netflix_shelf_enabled", false);
        String country = preferences.getString("netflix_country", "MY");
        if (!country.equals(loadedCountry)) {
            catalog = null; lastFetch = 0; index = 0; loadedCountry = country;
        }
        generation++;
        if (next != null) UI.removeCallbacks(next);
        if (!active) {
            hide(a);
            PlacesShelf.refresh(a);
            if (!preferences.getBoolean("places_wallpaper_enabled", true)) {
                ImageView fallback = a.findViewById(R.id.netflixWallpaper);
                if (fallback != null) {
                    fallback.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    fallback.setImageResource(R.drawable.aurora_wallpaper);
                    fallback.setTranslationY(0);
                    fallback.setAlpha(1f);
                    fallback.setVisibility(View.VISIBLE);
                }
            }
            return;
        }
        PlacesShelf.pause(a);
        hide(a);
        ImageView background = a.findViewById(R.id.netflixWallpaper);
        View shade = a.findViewById(R.id.netflixWallpaperShade);
        if (background == null || shade == null) return;
        if (catalog == null) {
            try {
                String saved = preferences.getString("netflix_catalog_cache_" + country, "");
                if (!saved.isEmpty()) catalog = new JSONArray(saved);
                lastFetch = preferences.getLong("netflix_catalog_cache_time_" + country, 0);
            } catch (Exception ignored) { catalog = null; }
        }
        if (catalog != null && catalog.length() > 0) showNext(a, generation);
        if (fetching || (catalog != null && System.currentTimeMillis()-lastFetch < CACHE_TTL)) return;
        fetching = true;
        IO.execute(() -> {
            try {
                JSONArray found = parseCatalog(fetchText(ENDPOINT + country + "&limit=21"));
                if (found.length() == 0) throw new IllegalStateException("No catalog records");
                UI.post(() -> {
                    fetching = false;
                    if (a.isFinishing() || a.isDestroyed()) return;
                    catalog = found;
                    lastFetch = System.currentTimeMillis();
                    preferences.edit().putString("netflix_catalog_cache_" + country, found.toString())
                            .putLong("netflix_catalog_cache_time_" + country, lastFetch).apply();
                    if (active && background.getVisibility() != View.VISIBLE) showNext(a, generation);
                });
            } catch (Exception error) {
                android.util.Log.w("NetflixWallpaper", "Netflix catalog unavailable", error);
                UI.post(() -> { fetching = false; });
            }
        });
    }
    /** Slide the entire decorative poster strip away as app rows scroll, restoring it on upward scroll. */
    static void onAppGridScrolled(Activity a, int scrollOffsetPx) {
        float shift = -Math.max(0, Math.min(scrollOffsetPx, Math.round(460 * a.getResources().getDisplayMetrics().density)));
        SharedPreferences preferences = a.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        boolean fixedBackground = !preferences.getBoolean("netflix_shelf_enabled", false);
        View wallpaper = a.findViewById(R.id.netflixWallpaper);
        View shade = a.findViewById(R.id.netflixWallpaperShade);
        View label = a.findViewById(R.id.netflixWallpaperTitle);
        // A full-screen scene must keep covering the clock even after a long grid scroll.
        if (wallpaper != null) wallpaper.setTranslationY(fixedBackground ? 0 : shift);
        if (shade != null) shade.setTranslationY(fixedBackground ? 0 : shift);
        if (label != null) label.setTranslationY(fixedBackground ? 0 : shift);
        PlacesShelf.onScrolled(a, shift);
    }
    static void pause(Activity a) {
        active = false;
        generation++;
        if (next != null) UI.removeCallbacks(next);
        PlacesShelf.pause(a);
    }
    private static void hide(Activity a) {
        ImageView image = a.findViewById(R.id.netflixWallpaper);
        View shade = a.findViewById(R.id.netflixWallpaperShade);
        TextView title = a.findViewById(R.id.netflixWallpaperTitle);
        if (image != null) { image.animate().cancel(); image.setVisibility(View.GONE); image.setImageDrawable(null); }
        if (shade != null) shade.setVisibility(View.GONE);
        if (title != null) title.setVisibility(View.GONE);
    }
    private static void showNext(Activity a, int token) {
        if (!active || token != generation || catalog == null || catalog.length() == 0) return;
        List<JSONObject> choices = new ArrayList<>();
        for (int i=0; i<catalog.length(); i++) {
            JSONObject obj = catalog.optJSONObject(i);
            if (obj != null && !posterUrl(obj).isEmpty()) choices.add(obj);
        }
        if (choices.isEmpty()) { hide(a); return; }
        final int count = Math.min(posterCount, choices.size());
        final int start = index % choices.size();
        index = (index + count) % choices.size();
        IO.execute(() -> {
            Bitmap picture = null;
            try {
                List<Bitmap> posters = new ArrayList<>();
                List<String> metas = new ArrayList<>();
                for (int i=0; i<count; i++) {
                    JSONObject entry = choices.get((start+i) % choices.size());
                    Bitmap poster = null;
                    try {
                        byte[] bytes = fetchImage(posterUrl(entry));
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 1;
                        while (bounds.outWidth / options.inSampleSize > 420 ||
                               bounds.outHeight / options.inSampleSize > 700) options.inSampleSize *= 2;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        poster = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    } catch (Exception error) {
                        android.util.Log.w("NetflixWallpaper", "Poster unavailable", error);
                    }
                    posters.add(poster);
                    metas.add(releaseMeta(entry));
                }
                picture = collageCanvas(posters, metas);
                for (Bitmap poster : posters) if (poster != null && !poster.isRecycled()) poster.recycle();
            } catch (Exception error) {
                android.util.Log.w("NetflixWallpaper", "Unable to render collage", error);
            }
            final Bitmap wallpaper = picture;
            UI.post(() -> {
                if (!active || token != generation || a.isFinishing() || a.isDestroyed()) {
                    if (wallpaper != null) wallpaper.recycle();
                    return;
                }
                if (wallpaper != null) {
                    ImageView background = a.findViewById(R.id.netflixWallpaper);
                    View shade = a.findViewById(R.id.netflixWallpaperShade);
                    TextView label = a.findViewById(R.id.netflixWallpaperTitle);
                    if (background != null) {
                        background.animate().cancel();
                        background.setScaleType(ImageView.ScaleType.FIT_XY);
                        background.setImageBitmap(wallpaper);
                        background.setVisibility(View.VISIBLE);
                        background.setAlpha(0f);
                        background.animate().alpha(1f).setDuration(900).start();
                    }
                    if (shade != null) { shade.setAlpha(0.35f); shade.setVisibility(View.VISIBLE); }
                    if (label != null) {
                        label.setText("NEW ON NETFLIX  \u00b7  " + countryName(loadedCountry));
                        label.setVisibility(View.VISIBLE);
                    }
                }
                next = () -> showNext(a, token);
                UI.postDelayed(next, INTERVAL);
            });
        });
    }

    /** Choose the portrait poster, never the 16:9 backdrop. */
    private static String posterUrl(JSONObject obj) {
        String s = first(obj, "poster_url", "posterUrl", "poster", "poster_path", "image", "image_url");
        if (s.isEmpty()) {
            JSONObject images = obj.optJSONObject("images");
            if (images != null) s = first(images, "poster_url", "poster", "poster_path");
        }
        if (s.startsWith("https://") || s.startsWith("http://")) return s;
        if (s.startsWith("/")) return "https://image.tmdb.org/t/p/w500" + s;
        return "";
    }

    /** Seven non-focusable portrait posters, with compact release metadata when supplied by the catalog. */
    private static Bitmap collageCanvas(List<Bitmap> posters, List<String> metas) {
        final int W=1280, H=720, count=posters.size();
        Bitmap output = Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(8,11,17));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Paint atmosphere = new Paint(Paint.ANTI_ALIAS_FLAG);
        atmosphere.setShader(new RadialGradient(W * 0.8f, 320, 630,
                0x30B30B20, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, atmosphere);
        // Dark display area intentionally leaves room for the launcher clock and application icons.
        int gap = count==7 ? 14 : 20;
        int available = 1180 - (count-1)*gap;
        int slotWidth = available/count;
        int posterHeight = Math.min(410, Math.round(slotWidth*1.48f));
        int top = 206;
        int x = (W - (slotWidth*count + gap*(count-1)))/2;
        Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFE1E5ED);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(13f);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        Path clip = new Path();
        for (int i=0;i<count;i++) {
            int left=x+i*(slotWidth+gap);
            float bottom = top + posterHeight + 36;
            RectF frame = new RectF(left - 4, top - 4, left + slotWidth + 4, bottom);
            card.setShader(new LinearGradient(0, top, 0, bottom,
                    0xFF252A34, 0xFF131720, Shader.TileMode.CLAMP));
            card.setShadowLayer(8, 0, 5, 0x80000000);
            canvas.drawRoundRect(frame, 11, 11, card);
            card.clearShadowLayer();
            clip.reset();
            clip.addRoundRect(new RectF(left, top, left + slotWidth, top + posterHeight),
                    8, 8, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            Bitmap poster=posters.get(i);
            if(poster!=null) {
                // Fit entire portrait within each card. Never zoom or crop titles/artwork.
                float scale=Math.min(slotWidth/(float)poster.getWidth(),posterHeight/(float)poster.getHeight());
                int pw=Math.round(poster.getWidth()*scale), ph=Math.round(poster.getHeight()*scale);
                int px=left+(slotWidth-pw)/2, py=top+(posterHeight-ph)/2;
                canvas.drawBitmap(poster,null,new Rect(px,py,px+pw,py+ph),paint);
            }
            canvas.restore();
            edge.setShader(new LinearGradient(0, top, 0, bottom,
                    0x66FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(frame, 11, 11, edge);
            // No movie titles: artwork already contains them. Show a single compact metadata line.
            String meta = i < metas.size() ? metas.get(i) : "";
            if (!meta.isEmpty()) {
                String fitted = fitTitle(meta, textPaint, slotWidth - 8f);
                canvas.drawText(fitted, left + slotWidth / 2f, top + posterHeight + 23, textPaint);
            }
        }
        return output;
    }
    /** Date fields vary among upstream catalog records. Do not invent missing release years. */
    private static String releaseYear(JSONObject obj) {
        String date = first(obj, "release_date", "first_air_date", "premiere_date",
                "date_released", "released", "releaseDate", "year", "release_year", "releaseYear");
        if (date.length() >= 4) {
            String yyyy = date.substring(0, 4);
            if (yyyy.matches("(?:19|20)[0-9]{2}")) return yyyy;
        }
        JSONObject details = obj.optJSONObject("details");
        if (details != null) return releaseYear(details);
        return "";
    }
    private static String releaseMeta(JSONObject obj) {
        String year = releaseYear(obj);
        String kind = contentKind(obj);
        if (!year.isEmpty() && !kind.isEmpty()) return year + " • " + kind;
        if (!year.isEmpty()) return year;
        return kind;
    }
    private static String contentKind(JSONObject obj) {
        String raw = first(obj, "type", "media_type", "content_type", "titleType", "show_type", "program_type", "format");
        if (raw.isEmpty()) {
            JSONObject details = obj.optJSONObject("details");
            if (details != null) raw = first(details, "type", "media_type", "content_type", "titleType", "show_type", "program_type", "format");
        }
        if (raw == null) raw = "";
        String s = raw.trim().toLowerCase(java.util.Locale.US);
        if (s.isEmpty()) return "";
        if (s.contains("documentary") || s.equals("doc") || s.equals("docs")) return "Documentary";
        if (s.contains("series") || s.contains("tv") || s.contains("show") || s.contains("season") || s.contains("episode")) return "Series";
        if (s.contains("movie") || s.contains("film") || s.contains("feature")) return "Movie";
        return capitalizeKind(s);
    }
    private static String capitalizeKind(String s) {
        String[] parts = s.replace('_', ' ').replace('-', ' ').trim().split("\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.toString();
    }
    private static String countryName(String country) {
        switch (country) {
            case "US": return "UNITED STATES";
            case "GB": return "UNITED KINGDOM";
            case "SG": return "SINGAPORE";
            case "JP": return "JAPAN";
            case "KR": return "SOUTH KOREA";
            case "IN": return "INDIA";
            case "AU": return "AUSTRALIA";
            default: return "MALAYSIA";
        }
    }
    private static String fitTitle(String text, Paint paint, float width) {
        if(paint.measureText(text)<=width) return text;
        int end=text.length();
        while(end>1 && paint.measureText(text,0,end)>width) end--;
        int space=text.lastIndexOf(' ',end);
        if(space>end/2) end=space;
        return text.substring(0,Math.max(1,end)).trim();
    }
    private static String first(JSONObject obj,String... keys) {
        for(String k:keys) { String v=obj.optString(k,""); if(!v.isEmpty() && !"null".equals(v)) return v; }
        return "";
    }
    private static JSONArray parseCatalog(String raw) throws Exception {
        String trim=raw.trim();
        if(trim.startsWith("[")) return new JSONArray(trim);
        JSONObject root=new JSONObject(trim);
        for(String k:new String[]{"results","titles","items","releases","movies","shows"}) {
            JSONArray arr=root.optJSONArray(k); if(arr!=null)return arr;
        }
        for(String outer:new String[]{"data","result","payload"}) {
            JSONObject ob=root.optJSONObject(outer); if(ob==null)continue;
            for(String k:new String[]{"results","titles","items","releases","movies","shows"}) {
                JSONArray arr=ob.optJSONArray(k); if(arr!=null)return arr;
            }
        }
        return new JSONArray();
    }
    private static String fetchText(String url) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent","CleanYourTV-NetflixCollage/6");
        try(InputStream in=connection.getInputStream();BufferedReader reader=new BufferedReader(new InputStreamReader(in,"UTF-8"))) {
            StringBuilder b=new StringBuilder();String line;while((line=reader.readLine())!=null)b.append(line);return b.toString();
        } finally { connection.disconnect(); }
    }
    private static byte[] fetchImage(String url) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
        try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int n;int total=0;
            while((n=in.read(buf))!=-1) { total+=n; if(total>5000000)throw new IllegalStateException("Image too large");out.write(buf,0,n); }
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }
}
