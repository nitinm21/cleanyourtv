package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ThemeCatalogTest {
    @Test public void themeIdsBecomeReadableLabels() {
        assertEquals("Family Tech", ThemeCatalog.displayName("family-tech"));
        assertEquals("Six Columns", ThemeCatalog.displayName("six-columns"));
    }
}
