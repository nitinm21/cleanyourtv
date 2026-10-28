package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    private static final Pattern THEME_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 3 && "themes".equals(segments.get(0)) && validThemeId(segments.get(1))) {
            String name = segments.get(2);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name)
                    ? "themes/" + segments.get(1) + "/" + name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        if (segments.size() == 4 && "themes".equals(segments.get(0)) && validThemeId(segments.get(1))
                && WALLPAPERS.equals(segments.get(2))
                && (collectionImage(segments.get(3)) || WallpaperCaptions.FILE_NAME.equals(segments.get(3)))) {
            return "themes/" + segments.get(1) + "/" + WALLPAPERS + "/" + segments.get(3);
        }
        return null;
    }

    /** The collection folder to clear for a supported collection-delete URI. */
    static String collectionPath(List<String> segments) {
        if (segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0))) return WALLPAPERS;
        if (segments != null && segments.size() == 3 && "themes".equals(segments.get(0))
                && validThemeId(segments.get(1)) && WALLPAPERS.equals(segments.get(2))) {
            return "themes/" + segments.get(1) + "/" + WALLPAPERS;
        }
        return null;
    }

    static boolean isCollection(List<String> segments) {
        return collectionPath(segments) != null;
    }

    static boolean validThemeId(String id) {
        return id != null && THEME_ID.matcher(id).matches();
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
