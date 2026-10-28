package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** focus_sweep: the band starts one tile width to the left and ends one width to the right. */
public class GlassSweepTest {

    @Test
    public void bandCrossesTheWholeTile() {
        assertEquals(-200f, GlassView.sweepOffset(0f, 200f), 0f);
        assertEquals(0f, GlassView.sweepOffset(0.5f, 200f), 0f);
        assertEquals(200f, GlassView.sweepOffset(1f, 200f), 0f);
    }
}
