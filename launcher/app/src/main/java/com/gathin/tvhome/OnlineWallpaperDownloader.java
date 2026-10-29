package com.gathin.tvhome;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class OnlineWallpaperDownloader {
    private static final String TAG = "TvHome.OnlineWallpaper";
    private static final long INTERVAL_MINUTES = 1;
    private final File directory;
    private final Consumer<File> callback;
    private ScheduledExecutorService executor;

    OnlineWallpaperDownloader(File directory, Consumer<File> callback) {
        this.directory = directory;
        this.callback = callback;
    }

    synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::download, 0,
                INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void download() {
        HttpURLConnection connection = null;
        File temporary = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;

            connection = (HttpURLConnection)
                    new URL("https://www.sourcesplash.com/i/random?q=nature&w=1920&h=1080").openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "CleanYourTV/2.7");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase().startsWith("image/")) return;

            temporary = File.createTempFile("online_", ".part", directory);
            long total = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 12L * 1024 * 1024) {
                        throw new java.io.IOException("Image too large");
                    }
                    out.write(buffer, 0, count);
                }
            }

            if (total < 1024) throw new java.io.IOException("Image too small");

            File image = new File(directory,
                    "online_" + System.currentTimeMillis() + ".jpg");
            if (!temporary.renameTo(image)) {
                throw new java.io.IOException("Could not save image");
            }
            temporary = null;

            File[] cached = directory.listFiles(
                    (dir, name) -> name.startsWith("online_") && name.endsWith(".jpg"));
            if (cached != null && cached.length > 24) {
                Arrays.sort(cached, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < cached.length - 24; i++) cached[i].delete();
            }

            callback.accept(image);
            Log.i(TAG, "Downloaded wallpaper: " + image.getName());
        } catch (Exception e) {
            Log.w(TAG, "Online wallpaper download failed", e);
        } finally {
            if (temporary != null) temporary.delete();
            if (connection != null) connection.disconnect();
        }
    }

    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    synchronized void close() {
        stop();
    }
}
