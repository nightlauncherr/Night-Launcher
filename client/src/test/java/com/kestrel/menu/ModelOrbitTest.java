package com.kestrel.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelOrbitTest {
    private static final float EPS = 1e-3f;

    @Test
    public void draggingAcrossTurnsAFullCircleAndKeepsGoing() {
        ModelOrbit o = new ModelOrbit();
        o.press(100, 100);
        float perTurn = 360f / ModelOrbit.YAW_PER_UNIT;
        // A quarter turn at a time, so every side is passed.
        float[] expected = {-90f, 180f, 90f, 0f};
        for (int q = 1; q <= 4; q++) {
            o.move(100 + perTurn * q / 4f, 100);
            assertEquals("after " + q + " quarter(s)", expected[q - 1], o.yaw(), 0.01f);
        }
        // Past a full turn it continues (wrapped), it does not stop at 360.
        o.move(100 + perTurn * 1.25f, 100);
        assertEquals(-90f, o.yaw(), 0.01f);
    }

    @Test
    public void draggingRightTurnsTheFrontRight() {
        ModelOrbit o = new ModelOrbit();
        o.press(0, 0);
        o.move(10, 0);
        // Positive pose yaw faces the viewer's left (see HeadLook), so right is negative.
        assertTrue(o.yaw() < 0f);
    }

    @Test
    public void pressDoesNotJumpAndOnlyMovementRotates() {
        ModelOrbit o = new ModelOrbit();
        o.press(50, 50);
        o.move(80, 40);
        float yaw = o.yaw();
        float pitch = o.pitch();
        o.release();
        // A new grab far away from where the last one ended: no change until the pointer moves.
        o.press(300, 200);
        assertEquals(yaw, o.yaw(), EPS);
        assertEquals(pitch, o.pitch(), EPS);
        o.move(300, 200);
        assertEquals(yaw, o.yaw(), EPS);
    }

    @Test
    public void verticalDragTiltsTheViewWithinLimits() {
        ModelOrbit o = new ModelOrbit();
        o.press(0, 0);
        o.move(0, 10);
        assertEquals(10f * ModelOrbit.PITCH_PER_UNIT, o.pitch(), EPS);
        o.move(0, 10_000);
        assertEquals(ModelOrbit.MAX_PITCH, o.pitch(), EPS);
        o.move(0, -10_000);
        assertEquals(-ModelOrbit.MAX_PITCH, o.pitch(), EPS);
    }

    @Test
    public void releaseStopsRotationImmediately() {
        ModelOrbit o = new ModelOrbit();
        o.press(0, 0);
        o.move(40, 20);
        o.release();
        assertFalse(o.dragging());
        float yaw = o.yaw();
        float pitch = o.pitch();
        o.move(400, 300); // moves after release are ignored
        o.update(0.5f);   // and nothing keeps spinning (no inertia; the sway waits)
        assertEquals(yaw, o.yaw(), EPS);
        assertEquals(pitch, o.pitch(), EPS);
    }

    @Test
    public void rotationDependsOnMovementNotFrameRate() {
        ModelOrbit fast = new ModelOrbit();
        ModelOrbit slow = new ModelOrbit();
        fast.press(0, 0);
        slow.press(0, 0);
        for (int i = 1; i <= 240; i++) {
            fast.update(1f / 240f);
            fast.move(i * 0.5f, i * 0.25f);
        }
        for (int i = 1; i <= 30; i++) {
            slow.update(1f / 30f);
            slow.move(i * 4f, i * 2f);
        }
        assertEquals(slow.yaw(), fast.yaw(), 0.01f);
        assertEquals(slow.pitch(), fast.pitch(), 0.01f);
    }

    @Test
    public void idleSwayWaitsFadesInAndNeverFightsTheMouse() {
        ModelOrbit o = new ModelOrbit();
        o.press(0, 0);
        o.move(30, 0);
        o.release();
        float rest = o.yaw();
        // Nothing during the delay after a release.
        for (int i = 0; i < 10; i++) o.update((ModelOrbit.SWAY_DELAY - 0.1f) / 10f);
        assertEquals(rest, o.yaw(), EPS);
        // Then a small sway, bounded by its amplitude.
        float maxDev = 0f;
        for (int i = 0; i < 600; i++) {
            o.update(1f / 60f);
            maxDev = Math.max(maxDev, Math.abs(o.yaw() - rest));
        }
        assertTrue("sway should move a little: " + maxDev, maxDev > 1f);
        assertTrue("sway stays subtle: " + maxDev, maxDev <= ModelOrbit.SWAY_DEG + EPS);
        // Grabbing keeps the visible angle and stops the sway while held.
        float seen = o.yaw();
        o.press(0, 0);
        assertEquals(seen, o.yaw(), EPS);
        for (int i = 0; i < 300; i++) o.update(1f / 60f);
        assertEquals(seen, o.yaw(), EPS);
    }

    @Test
    public void wrapKeepsAnglesInRange() {
        assertEquals(0f, ModelOrbit.wrap(360f), EPS);
        assertEquals(180f, ModelOrbit.wrap(-180f), EPS);
        assertEquals(-170f, ModelOrbit.wrap(190f), EPS);
        assertEquals(10f, ModelOrbit.wrap(730f), EPS);
    }
}
