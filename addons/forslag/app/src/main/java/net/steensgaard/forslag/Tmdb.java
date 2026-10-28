package net.steensgaard.forslag;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * GET requests to TMDb, and to DRTV, Wikidata and the image servers, kept on disk for a few hours so opening the
 * app again costs nothing. When a server can't be reached, the last answer is used however old it is. Only TMDb
 * gets the key.
 */
final class Tmdb {
    private static final String AGENT = "Forslag/" + BuildConfig.VERSION_NAME + " (Android TV app)";
    private static final String API = "https://api.themoviedb.org/3";

    /** TMDb answered 401: the key is wrong or revoked. */
    static final class KeyRefused extends IOException {
        KeyRefused() {
            super("401");
        }
    }

    private final String key;
    private final File dir;
    private final long freshMs;

    Tmdb(String key, File cacheDir, int hours) {
        this.key = key;
        this.dir = cacheDir;
        this.freshMs = hours * 3_600_000L;
        dir.mkdirs();
    }

    JSONObject json(String path) throws Exception {
        return new JSONObject(new String(cached(API + path, true), StandardCharsets.UTF_8));
    }

    /** JSON from any other server, without the key. */
    JSONObject open(String url) throws Exception {
        return new JSONObject(new String(cached(url, false), StandardCharsets.UTF_8));
    }

    byte[] image(String url) throws Exception {
        return cached(url, false);
    }

    private byte[] cached(String url, boolean api) throws Exception {
        File f = new File(dir, sha1(url));
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < freshMs) return read(new FileInputStream(f));
        try {
            byte[] body = fetch(url, api);
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            }
            if (!tmp.renameTo(f)) tmp.delete();
            return body;
        } catch (KeyRefused e) {
            throw e;
        } catch (Exception e) {
            if (f.exists()) return read(new FileInputStream(f));
            throw e;
        }
    }

    /** A v4 read access token (a JWT, "eyJ…") goes in a header; a v3 key goes in the query. */
    private byte[] fetch(String url, boolean api) throws Exception {
        boolean bearer = key.startsWith("eyJ");
        String full = api && !bearer ? url + (url.contains("?") ? "&" : "?") + "api_key=" + key : url;
        HttpURLConnection c = (HttpURLConnection) new URL(full).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        if (api) c.setRequestProperty("Accept", "application/json");
        // Wikidata turns away requests without one; DR asks for an ordinary one.
        c.setRequestProperty("User-Agent", AGENT);
        if (api && bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new KeyRefused();
            if (code != 200) throw new IOException("HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** File names from the URL without the key, so the key never lands on disk. */
    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }
}
