package com.kestrel.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks the head direction through the same transform chain the renderer
 * uses (scale(-s, s, s), rotate(180 + bodyYaw) about Y, then ModelRenderer's
 * Y-then-X head rotation), applied to the face normal (0, 0, -1) in model
 * space. Screen space: +x right, +y down, +z towards the viewer.
 */
public class HeadLookTest {
    private static final float REST_YAW = EntranceAnimation.REST_YAW;
    private static final float DEPTH = 160f;

    /** Face direction on screen for a body yaw (degrees) and head angles (radians). */
    private static double[] face(float bodyYawDeg, float headYaw, float headPitch) {
        double[] v = {0, 0, -1};
        v = rotX(v, headPitch);
        v = rotY(v, headYaw);
        v = rotY(v, Math.toRadians(180.0 + bodyYawDeg));
        return new double[]{-v[0], v[1], v[2]};
    }

    private static double[] rotX(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
    }

    private static double[] rotY(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }

    private static double[] look(float dx, float dy) {
        float[] out = new float[2];
        HeadLook.target(dx, dy, DEPTH, REST_YAW, out);
        return face(REST_YAW, out[0], out[1]);
    }

    @Test
    public void faceAtRestPointsAtTheViewer() {
        double[] f = face(0f, 0f, 0f);
        assertEquals(0.0, f[0], 1e-9);
        assertEquals(0.0, f[1], 1e-9);
        assertEquals(1.0, f[2], 1e-9);
    }

    @Test
    public void looksTowardsThePointerInAllFourDirections() {
        assertTrue("right", look(120f, 0f)[0] > 0.3);
        assertTrue("left", look(-120f, 0f)[0] < -0.3);
        assertTrue("up", look(0f, -120f)[1] < -0.3);
        assertTrue("down", look(0f, 120f)[1] > 0.3);
        // Always still facing out of the screen.
        assertTrue(look(120f, 0f)[2] > 0.0);
    }

    @Test
    public void pointerInFrontOfTheHeadFacesTheViewerSquarely() {
        double[] f = look(0f, 0f);
        assertEquals(0.0, f[0], 1e-6);
        assertEquals(0.0, f[1], 1e-6);
    }

    @Test
    public void aimsExactlyAtThePointerWithinTheLimits() {
        float dx = 70f, dy = -40f;
        double[] f = look(dx, dy);
        double len = Math.sqrt(dx * dx + dy * dy + DEPTH * DEPTH);
        assertEquals(dx / len, f[0], 1e-5);
        assertEquals(dy / len, f[1], 1e-5);
        assertEquals(DEPTH / len, f[2], 1e-5);
    }

    @Test
    public void staysWithinNaturalLimits() {
        float[] out = new float[2];
        HeadLook.target(-100000f, -100000f, DEPTH, REST_YAW, out);
        assertEquals(HeadLook.MAX_YAW, out[0], 1e-6);
        assertEquals(-HeadLook.MAX_UP, out[1], 1e-6);
        HeadLook.target(100000f, 100000f, DEPTH, REST_YAW, out);
        assertEquals(-HeadLook.MAX_YAW, out[0], 1e-6);
        assertEquals(HeadLook.MAX_DOWN, out[1], 1e-6);
    }
}
