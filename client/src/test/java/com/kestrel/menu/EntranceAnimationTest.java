package com.kestrel.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EntranceAnimationTest {
    @Test
    public void startsOffScreenRunningAndEndsStandingStill() {
        EntranceAnimation.Pose p = new EntranceAnimation.Pose();
        EntranceAnimation.pose(0f, p);
        assertEquals(0f, p.progress, 0f);
        assertEquals(1f, p.speed, 0f);
        assertEquals(EntranceAnimation.RUN_YAW, p.yaw, 1e-4f);
        EntranceAnimation.pose(5f, p);
        assertEquals(1f, p.progress, 0f);
        assertEquals(0f, p.speed, 0f);
        assertEquals(0f, p.stride, 0f);
        assertEquals("faces the camera", EntranceAnimation.REST_YAW, p.yaw, 1e-4f);
        assertEquals(0f, p.lean, 0f);
        assertEquals(0f, p.bob, 0f);
        assertEquals(1f, p.idle, 1e-6f);
    }

    @Test
    public void reachesTheSpotExactlyOnArrivalWithoutOvershoot() {
        assertEquals(1f, EntranceAnimation.progress(EntranceAnimation.ARRIVE), 1e-5f);
        float prev = 0f;
        for (int i = 1; i <= 4000; i++) {
            float t = i / 4000f * 2f;
            float p = EntranceAnimation.progress(t);
            assertTrue("never moves backwards", p >= prev - 1e-6f);
            assertTrue("never overshoots", p <= 1f + 1e-6f);
            prev = p;
        }
    }

    @Test
    public void velocityIsContinuousSoThereIsNoSnap() {
        // Numerical velocity just before/after the switch to braking, and at arrival.
        float h = 1e-3f;
        float c = EntranceAnimation.CRUISE;
        float vBefore = (EntranceAnimation.progress(c) - EntranceAnimation.progress(c - h)) / h;
        float vAfter = (EntranceAnimation.progress(c + h) - EntranceAnimation.progress(c)) / h;
        assertEquals(vBefore, vAfter, 0.01f);
        float a = EntranceAnimation.ARRIVE;
        float vArrive = (EntranceAnimation.progress(a) - EntranceAnimation.progress(a - h)) / h;
        assertTrue("almost stopped when it arrives: " + vArrive, vArrive < 0.01f);
    }

    @Test
    public void strideKeepsPaceWithTheGroundAndFadesOut() {
        EntranceAnimation.Pose p = new EntranceAnimation.Pose();
        // Phase per unit of distance is constant (feet do not slide).
        EntranceAnimation.pose(0.2f, p);
        float r1 = p.phase / p.progress;
        EntranceAnimation.pose(0.9f, p);
        float r2 = p.phase / p.progress;
        assertEquals(r1, r2, 1e-3f);
        float prevStride = 2f;
        for (float t = EntranceAnimation.CRUISE; t <= EntranceAnimation.ARRIVE; t += 0.01f) {
            EntranceAnimation.pose(t, p);
            assertTrue("stride shrinks while braking", p.stride <= prevStride + 1e-6f);
            prevStride = p.stride;
        }
    }

    @Test
    public void sameTimeGivesTheSamePoseAtAnyFrameRate() {
        // The pose is a pure function of elapsed time: sampling at 30 or 240 FPS lands on the same values.
        EntranceAnimation.Pose a = new EntranceAnimation.Pose();
        EntranceAnimation.Pose b = new EntranceAnimation.Pose();
        float t30 = 0f;
        for (int i = 0; i < 30; i++) t30 += 1f / 30f;
        float t240 = 0f;
        for (int i = 0; i < 240; i++) t240 += 1f / 240f;
        EntranceAnimation.pose(t30, a);
        EntranceAnimation.pose(t240, b);
        assertEquals(a.progress, b.progress, 1e-4f);
        assertEquals(a.yaw, b.yaw, 1e-2f);
    }

    @Test
    public void turnsSmoothlyAndSettles() {
        assertFalse(EntranceAnimation.settled(EntranceAnimation.ARRIVE - 0.3f));
        assertTrue(EntranceAnimation.settled(EntranceAnimation.ARRIVE + 0.5f));
        EntranceAnimation.Pose p = new EntranceAnimation.Pose();
        float prev = EntranceAnimation.RUN_YAW;
        for (float t = 0f; t < 3f; t += 1f / 120f) {
            EntranceAnimation.pose(t, p);
            assertTrue("yaw only ever turns towards the camera", p.yaw <= prev + 1e-4f);
            assertTrue("no jump in the turn", prev - p.yaw < 3f);
            assertTrue("never turns past the rest angle", p.yaw >= EntranceAnimation.REST_YAW - 1e-4f);
            prev = p.yaw;
        }
    }
}
