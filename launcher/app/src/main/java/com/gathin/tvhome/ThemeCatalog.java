package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Theme packs staged on the TV by tools/theme.ps1 -InstallPicker. */
final class ThemeCatalog {
    static final String DIRECTORY = "themes";
    static final String SELECTED_THEME_KEY = "selected_theme";

    private ThemeCatalog() {}

    static final class Theme {
        final String id;
        final String name;
        final File directory;
        final File config;

        Theme(String id, File directory, File config) {
            this.id = id;
            this.name = displayName(id);
            this.directory = directory;
            this.config = config;
        }
    }

    static List<Theme> load(Context context) throws IOException {
        File files = Config.dir(context);
        File directory = files == null ? null : new File(files, DIRECTORY);
        if (directory == null || !directory.isDirectory()) return Collections.emptyList();
        File[] entries = directory.listFiles(File::isDirectory);
        if (entries == null) throw new IOException("Cannot read theme catalog");
        Arrays.sort(entries);
        List<Theme> themes = new ArrayList<>();
        for (File entry : entries) {
            if (!ThemeFiles.validThemeId(entry.getName())) continue;
            File config = new File(entry, Config.FILE_NAME);
            if (!config.isFile()) continue;
            readConfig(config);
            themes.add(new Theme(entry.getName(), entry, config));
        }
        return themes;
    }

    static void apply(Context context, Theme theme) throws IOException {
        File root = Config.dir(context);
        if (root == null || (!root.isDirectory() && !root.mkdirs())) {
            throw new IOException("Theme storage is unavailable");
        }
        clearActiveWallpapers(root);
        copy(theme.config, new File(root, Config.FILE_NAME));

        File singleWallpaper = new File(theme.directory, Config.WALLPAPER_NAME);
        if (singleWallpaper.isFile()) copy(singleWallpaper, new File(root, Config.WALLPAPER_NAME));

        File sourceCollection = new File(theme.directory, ThemeFiles.WALLPAPERS);
        List<File> images = WallpaperRotation.images(sourceCollection);
        File destinationCollection = new File(root, ThemeFiles.WALLPAPERS);
        if (!images.isEmpty() && !destinationCollection.isDirectory() && !destinationCollection.mkdirs()) {
            throw new IOException("Cannot create theme wallpaper folder");
        }
        for (File image : images) copy(image, new File(destinationCollection, image.getName()));
        File captions = new File(sourceCollection, WallpaperCaptions.FILE_NAME);
        if (captions.isFile()) copy(captions,
                new File(destinationCollection, WallpaperCaptions.FILE_NAME));

        File revision = new File(root, "theme_revision");
        try (FileOutputStream output = new FileOutputStream(revision)) {
            output.write(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                .remove(Config.DOCK_OPACITY_KEY).putString(SELECTED_THEME_KEY, theme.id).apply();
    }

    static String displayName(String id) {
        if ("six-columns".equals(id)) return "Six Columns";
        String[] words = id.replace('-', ' ').split(" ");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    private static JSONObject readConfig(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > 1024 * 1024) throw new IOException("Invalid theme configuration size");
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Cannot read " + file.getName());
                offset += count;
            }
            return new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Invalid theme configuration: " + file.getParentFile().getName(), e);
        }
    }

    private static void clearActiveWallpapers(File root) throws IOException {
        File wallpaper = new File(root, Config.WALLPAPER_NAME);
        if (wallpaper.exists() && !wallpaper.delete()) throw new IOException("Cannot replace theme wallpaper");
        File collection = new File(root, ThemeFiles.WALLPAPERS);
        for (File image : WallpaperRotation.images(collection)) {
            if (!image.delete()) throw new IOException("Cannot replace theme wallpaper collection");
        }
        File captions = new File(collection, WallpaperCaptions.FILE_NAME);
        if (captions.exists() && !captions.delete()) throw new IOException("Cannot replace theme captions");
    }

    private static void copy(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create theme folder");
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }
}
