package com.kestrel.animation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrailColorTest {
    private static int[] sword(int blade, int handle) {
        int[] px = new int[256];
        for (int i = 0; i < 16; i++) {
            px[i * 16 + (15 - i)] = blade;          // diagonal blade
            if (i > 11) px[i * 16 + (15 - i) - 1] = handle;
            px[i * 16] = 0xFF1A1A1A;                 // dark outline
        }
        return px;
    }

    @Test
    public void diamondGlowsCyanAndGoldYellow() {
        int c = TrailColor.fromPixels(sword(0xFF33EBCB, 0xFF4A3520));
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        assertTrue("cyan: " + Integer.toHexString(c), g > 200 && b > 150 && r < 120);
        int gold = TrailColor.fromPixels(sword(0xFFFCEE4B, 0xFF4A3520));
        assertTrue("yellow", ((gold >> 16) & 0xFF) > 230 && ((gold >> 8) & 0xFF) > 200 && (gold & 0xFF) < 150);
    }

    @Test
    public void greyBladesGetSteelAndEmptyIsSafe() {
        assertEquals(TrailColor.STEEL, TrailColor.fromPixels(sword(0xFFD8D8D8, 0xFF6B6B6B)));
        assertEquals(TrailColor.STEEL, TrailColor.fromPixels(new int[256]));
    }
}
