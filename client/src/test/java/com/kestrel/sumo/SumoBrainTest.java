package com.kestrel.sumo;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SumoBrainTest {
    private static SumoBrain brain(double skill, long seed) {
        return new SumoBrain(new SumoDifficulty(skill), new PlayerProfile(), new Random(seed));
    }

    private static SumoBrain.Body body(double x, double z, float yaw) {
        return new SumoBrain.Body().set(x, SumoArena.SURFACE_Y, z, yaw, 0f, true, 0);
    }

    /** World-space direction the keys move the body in (unit-ish, 0 when no key). */
    private static double[] moveDir(SumoBrain.Controls c) {
        double r = Math.toRadians(c.yaw);
        double x = -Math.sin(r) * c.forward + Math.cos(r) * c.strafe;
        double z = Math.cos(r) * c.forward + Math.sin(r) * c.strafe;
        return new double[]{x, z};
    }

    /** Move a body like walking keys would (roughly 0.2 blocks per tick) and take its new look. */
    private static void step(SumoBrain.Body b, SumoBrain.Controls c) {
        double[] m = moveDir(c);
        double l = Math.sqrt(m[0] * m[0] + m[1] * m[1]);
        if (l > 0) {
            b.x += m[0] / l * 0.2;
            b.z += m[1] / l * 0.2;
        }
        b.yaw = c.yaw;
        b.pitch = c.pitch;
    }

    @Test
    public void farAwayItWalksInAndSprintsWithoutClicking() {
        SumoBrain b = brain(0.5, 1);
        SumoBrain.Body self = body(5, 0.5, 90);
        SumoBrain.Body target = body(-6, 0.5, -90);
        boolean sprinted = false;
        for (int i = 0; i < 8; i++) {
            SumoBrain.Controls c = b.tick(self, target);
            assertFalse("no clicks from 11 blocks", c.attack);
            sprinted |= c.forward == 1f && c.sprint;
            b.tick(self, target);
        }
        assertTrue(sprinted);
    }

    @Test
    public void turnsNoFasterThanItsTurnRate() {
        SumoDifficulty d = new SumoDifficulty(1.0);
        SumoBrain b = new SumoBrain(d, new PlayerProfile(), new Random(2));
        SumoBrain.Body self = body(0.5, 0.5, 0);      // facing +Z
        SumoBrain.Body target = body(0.5, -4.5, 0);   // directly behind
        for (int i = 0; i < 20; i++) {
            float before = self.yaw;
            SumoBrain.Controls c = b.tick(self, target);
            float turned = Math.abs(SumoBrain.wrap(c.yaw - before));
            assertTrue("turned " + turned, turned <= d.maxTurnDeg() + 1e-3);
            self.yaw = c.yaw;
        }
    }

    @Test
    public void atTheEdgeItAlwaysMovesBackInwards() {
        for (long seed = 0; seed < 40; seed++) {
            SumoBrain b = brain(seed % 2 == 0 ? 0.05 : 1.0, seed);
            // 0.4 blocks from the east edge, the player to its north-west.
            SumoBrain.Body self = body(SumoArena.maxEdge() - 0.4, 0.5, 90);
            SumoBrain.Body target = body(SumoArena.maxEdge() - 3.0, -2.5, -90);
            for (int i = 0; i < 6; i++) {
                SumoBrain.Controls c = b.tick(self, target);
                double[] m = moveDir(c);
                assertTrue("seed " + seed + " tick " + i + " moves outward: " + m[0], m[0] < 0);
                assertFalse("no jumps at the edge", c.jump);
                self.yaw = c.yaw;
            }
        }
    }

    @Test
    public void strafesBothWaysWithVariedTiming() {
        SumoBrain b = brain(0.5, 3);
        SumoBrain.Body self = body(3, 0.5, 90);
        SumoBrain.Body target = body(0.5, 0.5, -90);
        int left = 0;
        int right = 0;
        int switches = 0;
        float last = 0;
        for (int i = 0; i < 400; i++) {
            SumoBrain.Controls c = b.tick(self, target);
            if (c.strafe > 0) left++;
            if (c.strafe < 0) right++;
            if (c.strafe != 0 && last != 0 && c.strafe != last) switches++;
            if (c.strafe != 0) last = c.strafe;
            self.yaw = c.yaw;
        }
        assertTrue("left " + left, left > 50);
        assertTrue("right " + right, right > 50);
        assertTrue("switches " + switches, switches >= 8);
    }

    @Test
    public void clicksAtAHumanRateOnlyInRange() {
        SumoDifficulty d = new SumoDifficulty(1.0);
        SumoBrain b = new SumoBrain(d, new PlayerProfile(), new Random(4));
        SumoBrain.Body self = body(3, 0.5, 90);
        SumoBrain.Body target = body(0.5, 0.5, -90); // 2.5 blocks, straight ahead
        int clicks = 0;
        for (int i = 0; i < 200; i++) { // 10 seconds
            SumoBrain.Controls c = b.tick(self, target);
            if (c.attack) clicks++;
            self.yaw = c.yaw;
            self.pitch = c.pitch;
        }
        assertTrue("clicks " + clicks, clicks >= 60);
        assertTrue("clicks " + clicks, clicks <= d.cps() * 10 * 1.25);

        SumoBrain far = brain(1.0, 5);
        SumoBrain.Body t2 = body(-3.5, 0.5, -90);
        for (int i = 0; i < 100; i++) {
            assertFalse(far.tick(self, t2).attack);
        }
    }

    @Test
    public void sprintHitIsFollowedByAWTap() {
        SumoBrain b = brain(1.0, 6);
        SumoBrain.Body self = body(5, 0.5, 90);
        SumoBrain.Body target = body(-2, 0.5, -90);
        b.tick(self, target);
        b.onHitLanded(true);
        boolean released = false;
        for (int i = 0; i < 3; i++) {
            SumoBrain.Controls c = b.tick(self, target);
            if (c.forward == 0f) {
                released = true;
                assertFalse("no sprint while W is released", c.sprint);
            }
        }
        assertTrue("W released within 3 ticks", released);
    }

    @Test
    public void sometimesSprintJumpsInFromRange() {
        int jumps = 0;
        for (long seed = 0; seed < 20; seed++) {
            SumoBrain b = brain(0.8, seed);
            SumoBrain.Body self = body(5, 0.5, 90);
            SumoBrain.Body target = body(-0.5, 0.5, -90);
            for (int i = 0; i < 20; i++) {
                if (b.tick(self, target).jump) jumps++;
            }
        }
        assertTrue("jumps " + jumps, jumps > 0);
    }

    @Test
    public void closingInBringsItWithinHittingRange() {
        SumoBrain b = brain(0.6, 7);
        SumoBrain.Body self = body(4, 0.5, 90);
        SumoBrain.Body target = body(-3, 0.5, -90);
        double closest = 99;
        for (int i = 0; i < 80; i++) {
            SumoBrain.Controls c = b.tick(self, target);
            step(self, c);
            closest = Math.min(closest, Math.hypot(self.x - target.x, self.z - target.z));
            assertTrue("stays on the platform", SumoArena.edgeDistance(self.x, self.z) > 0);
        }
        assertTrue("closest " + closest, closest < SumoBrain.REACH);
    }

    @Test
    public void learnsThePlayersStrafeHabitsAndJumps() {
        PlayerProfile p = new PlayerProfile();
        SumoBrain b = new SumoBrain(new SumoDifficulty(0.5), p, new Random(8));
        SumoBrain.Body self = body(-3, 0.5, -90); // facing +X: its left is -Z
        SumoBrain.Body target = body(1, 0.5, 90);
        for (int i = 0; i < 400; i++) {
            // Player strafes mostly to the bot's left (-Z), 12 ticks at a time, with short rights.
            int phase = i % 16;
            target.z += phase < 12 ? -0.1 : 0.1;
            boolean jump = i % 40 == 0;
            target.onGround = !jump;
            target.y = SumoArena.SURFACE_Y + (jump ? 0.4 : 0);
            SumoBrain.Controls c = b.tick(self, target);
            self.yaw = c.yaw;
        }
        assertTrue("prefers left: " + p.preferredLateral(), p.preferredLateral() > 0.3);
        assertTrue("strafe run " + p.expectedStrafeRun(), p.expectedStrafeRun() > 3);
        assertTrue("jumps " + p.jumpsPerSecond(), p.jumpsPerSecond() > 0.3);
        assertEquals(400 - 1, p.observedTicks);
    }

    @Test
    public void roundResetKeepsWhatWasLearned() {
        PlayerProfile p = new PlayerProfile();
        SumoBrain b = new SumoBrain(new SumoDifficulty(0.5), p, new Random(9));
        SumoBrain.Body self = body(-3, 0.5, -90);
        SumoBrain.Body target = body(1, 0.5, 90);
        for (int i = 0; i < 50; i++) {
            target.z -= 0.1;
            b.tick(self, target);
        }
        long ticks = p.leftTicks;
        b.resetRound();
        assertEquals(ticks, p.leftTicks);
        // The first tick after a reset has no previous position: nothing new recorded.
        b.tick(self, target);
        assertEquals(ticks, p.leftTicks);
    }

    @Test
    public void yawHelpersMatchMinecraft() {
        assertEquals(0.0, SumoBrain.yawTo(0, 0, 0, 5), 1e-9);    // +Z
        assertEquals(-90.0, SumoBrain.yawTo(0, 0, 5, 0), 1e-9);  // +X
        assertEquals(90.0, Math.abs(SumoBrain.wrap((float) SumoBrain.yawTo(0, 0, -5, 0))), 1e-4); // -X
        assertEquals(-170f, SumoBrain.wrap(190f), 1e-4);
    }
}
