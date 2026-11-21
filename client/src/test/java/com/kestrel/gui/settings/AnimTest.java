package com.kestrel.gui.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnimTest {
    @Test
    public void approachMovesTowardTargetAndSnaps() {
        float v = Anim.approach(0f, 1f, 18f, 0.016f);
        assertTrue(v > 0f && v < 1f);
        float x = 0f;
        for (int i = 0; i < 200; i++) x = Anim.approach(x, 1f, 18f, 0.016f);
        assertEquals(1f, x, 0f); // snapped exactly
    }

    @Test
    public void approachIsFrameRateIndependent() {
        float a = 0f;
        for (int i = 0; i < 6; i++) a = Anim.approach(a, 1f, 10f, 1f / 60f);
        float b = 0f;
        for (int i = 0; i < 2; i++) b = Anim.approach(b, 1f, 10f, 1f / 20f);
        assertEquals(a, b, 0.001f);
    }

    @Test
    public void approachIgnoresZeroOrNegativeTime() {
        assertEquals(0.3f, Anim.approach(0.3f, 1f, 18f, 0f), 0f);
        assertEquals(0.3f, Anim.approach(0.3f, 1f, 18f, -1f), 0f);
    }

    @Test
    public void progressClamps() {
        assertEquals(0f, Anim.progress(1000, 900, 100), 0f);
        assertEquals(0.5f, Anim.progress(1000, 1050, 100), 1e-6f);
        assertEquals(1f, Anim.progress(1000, 5000, 100), 0f);
        assertEquals(1f, Anim.progress(1000, 1000, 0), 0f);
    }

    @Test
    public void easeOutEndpoints() {
        assertEquals(0f, Anim.easeOut(0f), 1e-6f);
        assertEquals(1f, Anim.easeOut(1f), 1e-6f);
        assertTrue(Anim.easeOut(0.5f) > 0.5f);
    }

    @Test
    public void mixAndFade() {
        assertEquals(0xFF000000, Anim.mix(0xFF000000, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, Anim.mix(0xFF000000, 0xFFFFFFFF, 1f));
        assertEquals(0xFFFFFFFF, Anim.mix(0xFF000000, 0xFFFFFFFF, 7f)); // clamped
        assertEquals(0x80808080, Anim.mix(0x00000000, 0xFFFFFFFF, 0.5f) & 0xFFFFFFFF);
        assertEquals(0x00A16BEF, Anim.fade(0xFFA16BEF, 0f));
        assertEquals(0x80A16BEF, Anim.fade(0xFFA16BEF, 128f / 255f));
        assertEquals(0xFFA16BEF, Anim.fade(0xFFA16BEF, 3f));
    }
}
