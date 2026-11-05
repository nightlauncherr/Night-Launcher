package com.kestrel.animation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnimationCoreTest {
    private static final long MS = 1_000_000L;

    @Test
    public void curveStartsAndEndsAtRestAndHitsEveryKey() {
        float[] out = new float[SwingStyle.CHANNELS];
        SwingStyle.SLASH.sample(0f, out);
        for (float v : out) assertEquals(0f, v, 1e-6f);
        SwingStyle.SLASH.sample(1f, out);
        for (int i = 0; i < SwingStyle.CHANNELS; i++) {
            // The slash makes one full turn: 360 degrees is the rest orientation again.
            float rest = i == SwingStyle.RZ ? 360f : 0f;
            assertEquals(rest, out[i], 1e-6f);
        }
        SwingStyle.SLASH.sample(0.38f, out);
        assertEquals(140f, out[SwingStyle.RZ], 1e-4f);
    }

    @Test
    public void slashEndPoseIsTheRestPose() {
        Mat4 end = new Mat4();
        Mat4 rest = new Mat4();
        float[] pose = new float[SwingStyle.CHANNELS];
        SwingStyle.KESTREL.pose(1f, 1f, pose);
        SwingStyle.KESTREL.apply(end, 0f, 1f, pose, 0f, new float[2], 1f);
        SwingStyle.vanillaTransform(rest, 0f, 0f);
        for (int i = 0; i < 16; i++) assertEquals(rest.m[i], end.m[i], 1e-4f);
    }

    @Test
    public void curveIsSmoothWithoutJumpsOrOvershoot() {
        float[] a = new float[SwingStyle.CHANNELS];
        float[] b = new float[SwingStyle.CHANNELS];
        float minRz = 0f;
        int steps = 2000;
        SwingStyle.SLASH.sample(0f, a);
        for (int i = 1; i <= steps; i++) {
            SwingStyle.SLASH.sample(i / (float) steps, b);
            // At 2000 samples no channel may move more than ~1.5 degrees per step: no snap.
            assertTrue("no jump at " + i, Math.abs(b[SwingStyle.RZ] - a[SwingStyle.RZ]) < 1.5f);
            if (i > steps / 5) {
                assertTrue("after the wind-up the sweep only goes forward", b[SwingStyle.RZ] >= a[SwingStyle.RZ] - 1e-4f);
            }
            assertTrue("never past a full turn", b[SwingStyle.RZ] <= 360f + 1e-3f);
            minRz = Math.min(minRz, b[SwingStyle.RZ]);
            System.arraycopy(b, 0, a, 0, a.length);
        }
        assertEquals("never past the wind-up key", -25f, minRz, 1e-3f);
    }

    @Test
    public void rejectsBadKeys() {
        try {
            new SwingCurve(new float[] {0f, 0.5f, 0.4f, 1f}, new float[][] {{0, 1, 2, 0}});
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void swingRunsOnWallClockTime() {
        SwingState s = new SwingState();
        float[] pose = new float[SwingStyle.CHANNELS];
        assertEquals(1f, s.progress(0, 0.3f), 0f);
        s.startSwing(1000 * MS, pose);
        assertEquals(0f, s.progress(1000 * MS, 0.3f), 0f);
        assertEquals(0.5f, s.progress(1150 * MS, 0.3f), 1e-4f);
        assertEquals("speed 2 halves the time", 1f, s.progress(1150 * MS, 0.15f), 1e-4f);
        assertFalse(s.isSwinging(1300 * MS, 0.3f));
    }

    @Test
    public void restartMidSwingCrossFadesInsteadOfSnapping() {
        SwingState s = new SwingState();
        float[] pose = new float[SwingStyle.CHANNELS];
        s.startSwing(0, pose);
        SwingStyle.KESTREL.pose(s.progress(200 * MS, 0.3f), 1f, pose); // mid follow-through
        float before = pose[SwingStyle.RZ];
        s.startSwing(200 * MS, pose); // spam click
        float[] next = new float[SwingStyle.CHANNELS];
        SwingStyle.KESTREL.pose(s.progress(200 * MS, 0.3f), 1f, next);
        s.blend(200 * MS, next);
        assertEquals("first frame after restart is where we were (same orientation)", 0f,
                wrap(before - next[SwingStyle.RZ]), 1e-3f);
        SwingStyle.KESTREL.pose(s.progress(300 * MS, 0.3f), 1f, next);
        float unblended = next[SwingStyle.RZ];
        s.blend(300 * MS, next);
        assertEquals("blend over after 70 ms", unblended, next[SwingStyle.RZ], 1e-5f);
    }

    @Test
    public void restartAfterAFullTurnDoesNotSpinBack() {
        SwingState s = new SwingState();
        float[] pose = new float[SwingStyle.CHANNELS];
        s.startSwing(0, pose);
        SwingStyle.KESTREL.pose(s.progress(290 * MS, 0.3f), 1f, pose); // nearly done: RZ close to 360
        s.startSwing(290 * MS, pose);
        float[] next = new float[SwingStyle.CHANNELS];
        float prev = Float.NaN;
        for (long ms = 290; ms <= 360; ms += 5) {
            SwingStyle.KESTREL.pose(s.progress(ms * MS, 0.3f), 1f, next);
            s.blend(ms * MS, next);
            float rz = next[SwingStyle.RZ];
            float wrapped = rz - 360f * Math.round(rz / 360f);
            if (!Float.isNaN(prev)) assertTrue("no whirl at " + ms + " ms", Math.abs(wrapped - prev) < 20f);
            prev = wrapped;
        }
    }

    private static float wrap(float deg) {
        return deg - 360f * Math.round(deg / 360f);
    }

    @Test
    public void blockEasesInRegardlessOfFrameRate() {
        for (int fps : new int[] {30, 144, 500}) {
            SwingState s = new SwingState();
            long t = 0;
            s.updateBlock(t, false, true);
            float b = 0f;
            for (int i = 0; i < fps / 4; i++) { // a quarter of a second
                t += 1_000_000_000L / fps;
                b = s.updateBlock(t, true, true);
            }
            assertTrue(fps + " fps: blocked within 0.25 s (" + b + ")", b > 0.97f);
        }
        SwingState s = new SwingState();
        assertEquals(1f, s.updateBlock(0, true, false), 0f);
    }

    @Test
    public void smoothStyleAtRestEqualsVanillaAtRest() {
        Mat4 a = new Mat4();
        Mat4 b = new Mat4();
        float[] zero = new float[SwingStyle.CHANNELS];
        SwingStyle.SMOOTH.apply(a, 0.25f, 1f, zero, 0f, new float[2], 1f);
        SwingStyle.vanillaTransform(b, 0.25f, 0f);
        for (int i = 0; i < 16; i++) assertEquals(b.m[i], a.m[i], 1e-5f);
        Mat4 c = new Mat4();
        SwingStyle.KESTREL.apply(c, 0.25f, 0f, zero, 0f, new float[2], 1f);
        for (int i = 0; i < 16; i++) assertEquals("Kestrel rest pose is vanilla's", b.m[i], c.m[i], 1e-5f);
    }

    @Test
    public void matrixMatchesGlComposition() {
        Mat4 m = new Mat4();
        m.translate(1f, 2f, 3f);
        m.rotate(90f, 0f, 0f, 1f);
        m.scale(2f, 2f, 2f);
        float[] p = new float[3];
        m.transformPoint(1f, 0f, 0f, p);
        // scale -> (2,0,0); rotate 90 about z -> (0,2,0); translate -> (1,4,3)
        assertEquals(1f, p[0], 1e-5f);
        assertEquals(4f, p[1], 1e-5f);
        assertEquals(3f, p[2], 1e-5f);
    }

    @Test
    public void equipEasingKeepsEndsAndIsMonotone() {
        assertEquals(0f, SwingStyle.easeEquip(0f), 0f);
        assertEquals(1f, SwingStyle.easeEquip(1f), 0f);
        float prev = 0f;
        for (int i = 1; i <= 100; i++) {
            float e = SwingStyle.easeEquip(i / 100f);
            assertTrue(e >= prev);
            prev = e;
        }
    }
}
