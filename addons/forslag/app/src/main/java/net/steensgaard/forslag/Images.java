package net.steensgaard.forslag;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Posters and backdrops: three downloads at a time, decoded as RGB_565, a small memory cache in front of Tmdb's disk one. */
final class Images {
    private final Tmdb tmdb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 24L << 20)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    Images(Tmdb tmdb) {
        this.tmdb = tmdb;
    }

    /** Shows the picture in v once it is loaded, unless v has been given another picture meanwhile. */
    void load(ImageView v, String url) {
        String key = url;
        v.setTag(key);
        Bitmap hit = memory.get(key);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap b = null;
            try {
                byte[] data = tmdb.image(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception e) {
                Log.w(MainActivity.TAG, "image: " + e);
            }
            if (b == null) return;
            memory.put(key, b);
            final Bitmap bitmap = b;
            main.post(() -> {
                if (key.equals(v.getTag())) v.setImageBitmap(bitmap);
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }
}
