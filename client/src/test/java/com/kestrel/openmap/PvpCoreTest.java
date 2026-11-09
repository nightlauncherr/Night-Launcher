package com.kestrel.openmap;

import com.kestrel.sumo.Playstyle;
import com.kestrel.sumo.SumoDifficulty;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PvpCoreTest {
    /** Flat ground at y=64 (stand at 65) with extra solid blocks. */
    static final class Blocks implements Terrain {
        final Set<String> solid = new HashSet<String>();
        final Set<String> lava = new HashSet<String>();

        void add(int x, int y, int z) {
            solid.add(x + "," + y + "," + z);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y <= 64 || solid.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean open(int x, int y, int z) {
            return !solid(x, y, z) && !lava.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            return lava.contains(x + "," + y + "," + z);
        }
    }

    @Test
    public void pathAroundAWall() {
        Blocks b = new Blocks();
        for (int z = -5; z <= 5; z++) {
            b.add(3, 65, z);
            b.add(3, 66, z);
            b.add(3, 67, z);
        }
        PathFinder pf = new PathFinder(b, 4000, 48);
        List<PathFinder.Node> p = pf.find(new PathFinder.Node(0, 65, 0), new PathFinder.Node(6, 65, 0), 0.5);
        assertFalse(p.isEmpty());
        PathFinder.Node end = p.get(p.size() - 1);
        assertEquals(6, end.x);
        assertEquals(0, end.z);
        for (PathFinder.Node n : p) assertFalse("walked through the wall at " + n, n.x == 3 && Math.abs(n.z) <= 5);
    }

    @Test
    public void stepsUpOneAndDropsThree() {
        Blocks b = new Blocks();
        b.add(1, 65, 0); // a one-block step
        PathFinder pf = new PathFinder(b, 4000, 48);
        assertEquals(66, pf.step(new PathFinder.Node(0, 65, 0), 1, 0).y);
        // Two high: no single step.
        b.add(1, 66, 0);
        assertNull(pf.step(new PathFinder.Node(0, 65, 0), 1, 0));
        // Down three from a 3-high pillar is fine, four is not.
        Blocks c = new Blocks();
        c.add(0, 65, 0);
        c.add(0, 66, 0);
        c.add(0, 67, 0);
        assertEquals(65, new PathFinder(c, 100, 8).step(new PathFinder.Node(0, 68, 0), 1, 0).y);
        c.add(0, 68, 0);
        assertNull(new PathFinder(c, 100, 8).step(new PathFinder.Node(0, 69, 0), 1, 0));
    }

    @Test
    public void noCornerCutting() {
        Blocks b = new Blocks();
        b.add(1, 65, 0);
        b.add(1, 66, 0);
        PathFinder pf = new PathFinder(b, 100, 8);
        assertNull(pf.step(new PathFinder.Node(0, 65, 0), 1, 1));
    }

    @Test
    public void avoidsLava() {
        Blocks b = new Blocks();
        for (int z = -6; z <= 6; z++) b.lava.add("2,65," + z);
        for (int z = -6; z <= 6; z++) b.lava.add("2,64," + z);
        PathFinder pf = new PathFinder(b, 4000, 48);
        List<PathFinder.Node> p = pf.find(new PathFinder.Node(0, 65, 0), new PathFinder.Node(4, 65, 0), 0.5);
        for (PathFinder.Node n : p) assertFalse(n.x == 2 && Math.abs(n.z) <= 6);
    }

    @Test
    public void ballisticsHitsWhatItAimsAt() {
        for (Ballistics.Kind k : Ballistics.Kind.values()) {
            for (double d : new double[]{4, 8, 12}) {
                Ballistics.Shot s = Ballistics.solve(k, d, 0.0, 60);
                assertNotNull(k + " at " + d, s);
                double h = Ballistics.heightAt(k, s.pitch, d, 60, null);
                assertEquals(k + " at " + d, 0.0, h, 0.6);
                assertTrue(s.pitch < 0); // aims a little up to cover the drop
            }
        }
        // Rods don't reach very far (strong drag).
        assertNull(Ballistics.solve(Ballistics.Kind.HOOK, 40, 0, 60));
    }

    private static PvpBrain brain(Terrain t, double skill, long seed) {
        return new PvpBrain(new SumoDifficulty(skill), new PvpProfile(), t, new Random(seed));
    }

    private static PvpBrain.Self self(double x, double z) {
        PvpBrain.Self s = new PvpBrain.Self();
        s.x = x;
        s.y = 65;
        s.z = z;
        s.onGround = true;
        s.health = 20;
        s.planks = 64;
        s.snowballs = 32;
        s.hasRod = true;
        s.hasSword = true;
        return s;
    }

    private static PvpBrain.Target target(double x, double z) {
        PvpBrain.Target t = new PvpBrain.Target();
        t.x = x;
        t.y = 65;
        t.z = z;
        t.onGround = true;
        t.visible = true;
        return t;
    }

    @Test
    public void chasesAFarTarget() {
        PvpBrain b = brain(new Blocks(), 0.7, 1);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(0.5, 30.5);
        PvpBrain.Controls c = null;
        for (int i = 0; i < 30; i++) {
            c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            me.slot = c.slot;
        }
        assertTrue(c.forward > 0.5);
        assertTrue(c.sprint || c.mode != PvpBrain.Mode.CHASE);
        // Facing +z (yaw 0) to run toward it, unless it is throwing something.
        if (c.mode == PvpBrain.Mode.CHASE) assertEquals(0f, PvpBrain.wrap(c.yaw), 15f);
    }

    @Test
    public void attacksInReachAndRespectsClickRate() {
        PvpBrain b = brain(new Blocks(), 0.9, 2);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(0.5, 3.0);
        int clicks = 0;
        int ticks = 200;
        for (int i = 0; i < ticks; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            me.slot = c.slot;
            if (c.attack) clicks++;
        }
        assertTrue("never attacked", clicks > 10);
        double cps = clicks / (ticks / 20.0);
        assertTrue("clicks too fast: " + cps, cps <= 13);
    }

    @Test
    public void usesRodAndSnowballsAtRangeAndOnlyWithItems() {
        PvpBrain b = brain(new Blocks(), 0.8, 3);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(0.5, 9.5);
        int rodUses = 0;
        int snowUses = 0;
        for (int i = 0; i < 1200; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            if (c.use && me.slot == PvpBrain.Slot.ROD) {
                rodUses++;
                me.hookOut = !me.hookOut;
                me.hookAge = 0;
            }
            if (c.use && me.slot == PvpBrain.Slot.SNOWBALL) {
                snowUses++;
                me.snowballs--;
            }
            if (me.hookOut) me.hookAge++;
            me.slot = c.slot;
        }
        assertTrue("no rod casts", rodUses > 0);
        assertTrue("no snowballs", snowUses > 0);
        assertTrue(me.snowballs >= 0);
        // Without snowballs or a rod it never tries.
        PvpBrain b2 = brain(new Blocks(), 0.8, 4);
        PvpBrain.Self bare = self(0.5, 0.5);
        bare.snowballs = 0;
        bare.hasRod = false;
        for (int i = 0; i < 600; i++) {
            PvpBrain.Controls c = b2.tick(bare, t);
            bare.yaw = c.yaw;
            bare.pitch = c.pitch;
            bare.slot = c.slot;
            assertFalse(c.slot == PvpBrain.Slot.SNOWBALL || c.slot == PvpBrain.Slot.ROD);
        }
    }

    @Test
    public void towersWhenTheTargetIsUpHighAndNeverWithoutPlanks() {
        PvpBrain b = brain(new Blocks(), 0.7, 5);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(2.5, 2.5);
        t.y = 69; // up on a tower
        boolean placed = false;
        int air = 0;
        for (int i = 0; i < 400 && !placed; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            me.slot = c.slot;
            // Crude jump: 1.2 up for 6 ticks, then land again.
            if (air > 0 && --air == 0) {
                me.onGround = true;
                me.y = 65;
            }
            if (c.jump && me.onGround) {
                me.onGround = false;
                me.y = 66.2;
                air = 6;
            }
            if (c.place) {
                placed = true;
                assertEquals(65, c.py); // under its feet
                assertEquals(PvpBrain.Slot.PLANKS, me.slot);
            }
        }
        assertTrue("never built up toward a player above it", placed);
        PvpBrain none = brain(new Blocks(), 0.7, 6);
        PvpBrain.Self empty = self(0.5, 0.5);
        empty.planks = 0;
        for (int i = 0; i < 400; i++) {
            PvpBrain.Controls c = none.tick(empty, t);
            empty.yaw = c.yaw;
            empty.pitch = c.pitch;
            empty.slot = c.slot;
            assertFalse(c.place);
        }
    }

    @Test
    public void profileLearnsAndStaysInRange() {
        PvpProfile p = new PvpProfile();
        PvpProfile.Observation o = new PvpProfile.Observation();
        o.ticks = 2400;
        o.strafeTicks = 400;
        o.strafeLeftTicks = 360;
        o.closeTicks = 400;
        o.closeJumps = 40;
        o.rodCasts = 20;
        o.blocksPlaced = 30;
        o.midTicks = 200;
        o.approachTicks = 180;
        for (int i = 0; i < 10; i++) p.learn(o);
        assertTrue(p.strafeLeft > 0.8);
        assertTrue(p.rush > 0.8);
        assertEquals(10, p.rodRate, 1.0);
        assertEquals(15, p.buildRate, 1.5);
        p.rodRate = Double.NaN;
        p.normalize();
        assertEquals(2, p.rodRate, 1e-9);
    }

    @Test
    public void playstylesChangeNothingIllegal() {
        for (Playstyle s : Playstyle.values()) {
            PvpBrain b = brain(new Blocks(), 1.0, 7);
            b.setPlaystyle(s);
            PvpBrain.Self me = self(0.5, 0.5);
            PvpBrain.Target t = target(0.5, 2.5);
            int clicks = 0;
            for (int i = 0; i < 100; i++) {
                PvpBrain.Controls c = b.tick(me, t);
                float turn = Math.abs(PvpBrain.wrap(c.yaw - me.yaw));
                assertTrue(s + " turned " + turn, turn <= new SumoDifficulty(1.0).maxTurnDeg() + 0.01);
                me.yaw = c.yaw;
                me.pitch = c.pitch;
                me.slot = c.slot;
                if (c.attack) clicks++;
            }
            assertTrue(clicks <= 100 / 20.0 * 13);
        }
    }
}
