package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ThemeFilesTest {
    @Test public void themeFilesAreAllowed() {
        assertEquals("config.json", ThemeFiles.path(Collections.singletonList("config.json")));
        assertEquals("wallpaper.jpg", ThemeFiles.path(Collections.singletonList("wallpaper.jpg")));
        assertEquals("theme_revision", ThemeFiles.path(Collections.singletonList("theme_revision")));
    }

    @Test public void collectionImagesAreAllowed() {
        assertEquals("wallpapers/dusk.jpg", ThemeFiles.path(Arrays.asList("wallpapers", "dusk.jpg")));
        assertEquals("wallpapers/Night_2.WEBP", ThemeFiles.path(Arrays.asList("wallpapers", "Night_2.WEBP")));
    }

    @Test public void theCollectionsCaptionsAreAllowed() {
        assertEquals("wallpapers/captions.txt", ThemeFiles.path(Arrays.asList("wallpapers", "captions.txt")));
        assertNull(ThemeFiles.path(Collections.singletonList("captions.txt")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", "notes.txt")));
        assertFalse(ThemeFiles.collectionImage("captions.txt"));
    }

    @Test public void catalogThemeFilesAreAllowed() {
        assertEquals("themes/glass/config.json",
                ThemeFiles.path(Arrays.asList("themes", "glass", "config.json")));
        assertEquals("themes/glass/wallpaper.jpg",
                ThemeFiles.path(Arrays.asList("themes", "glass", "wallpaper.jpg")));
        assertEquals("themes/glass/wallpapers/night.webp",
                ThemeFiles.path(Arrays.asList("themes", "glass", "wallpapers", "night.webp")));
        assertEquals("themes/glass/wallpapers/captions.txt",
                ThemeFiles.path(Arrays.asList("themes", "glass", "wallpapers", "captions.txt")));
        assertEquals("themes/glass/wallpapers",
                ThemeFiles.collectionPath(Arrays.asList("themes", "glass", "wallpapers")));
    }

    @Test public void anythingElseIsRefused() {
        assertNull(ThemeFiles.path(null));
        assertNull(ThemeFiles.path(Collections.emptyList()));
        assertNull(ThemeFiles.path(Collections.singletonList("cards.json")));
        assertNull(ThemeFiles.path(Collections.singletonList("..")));
        assertNull(ThemeFiles.path(Arrays.asList("..", "config.json")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", "..")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", "run.sh")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", "a b.jpg")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", "../config.jpg")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", ".jpg")));
        assertNull(ThemeFiles.path(Arrays.asList("other", "dusk.jpg")));
        assertNull(ThemeFiles.path(Arrays.asList("wallpapers", "sub", "dusk.jpg")));
        assertNull(ThemeFiles.path(Arrays.asList("themes", "..", "config.json")));
        assertNull(ThemeFiles.path(Arrays.asList("themes", "glass", "..")));
        assertNull(ThemeFiles.path(Arrays.asList("themes", "glass", "wallpapers", "../x.jpg")));
        assertNull(ThemeFiles.path(Arrays.asList("themes", "glass", "unknown.json")));
    }

    @Test public void onlyTheBareFolderIsTheCollection() {
        assertTrue(ThemeFiles.isCollection(Collections.singletonList("wallpapers")));
        assertFalse(ThemeFiles.isCollection(Arrays.asList("wallpapers", "dusk.jpg")));
        assertFalse(ThemeFiles.isCollection(Collections.singletonList("config.json")));
        assertTrue(ThemeFiles.isCollection(Arrays.asList("themes", "glass", "wallpapers")));
        assertFalse(ThemeFiles.isCollection(Arrays.asList("themes", "..", "wallpapers")));
    }
}
