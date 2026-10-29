package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fetches the current temperature for the header pill from open-meteo, off the main thread. */
final class WeatherFetcher {
    interface Callback {
        void onTemperature(String text);
    }

    private WeatherFetcher() {
    }

    static void fetch(final String lat, final String lon, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                try {
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    int i = sb.indexOf("\"temperature\":");
                    if (i >= 0) {
                        int e = sb.indexOf(",", i);
                        text = sb.substring(i + 14, e) + "\u00b0";
                    }
                } catch (Exception ignored) {
                    // no network, no pill
                }
                final String out = text;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onTemperature(out);
                    }
                });
            }
        }).start();
    }
}
