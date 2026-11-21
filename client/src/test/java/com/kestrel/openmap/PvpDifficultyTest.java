package com.kestrel.openmap;

import com.kestrel.sumo.DifficultyMode;
import com.kestrel.sumo.SumoDifficulty;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * Difficulty as overall speed (perceiving, deciding, turning, moving) and the
 * defensive low-health play, simulated with simple movement on flat ground.
 */
public class PvpDifficultyTest {
    private static final DifficultyMode[] LEVELS = {
            DifficultyMode.EASY, DifficultyMode.MEDIUM, DifficultyMode.HARD, DifficultyMode.EXPERT};
    private static final int SEEDS = 40;

    private static PvpBrain brain(DifficultyMode m, long seed) {
        return new PvpBrain(new SumoDifficulty(m.skill(0)), new PvpProfile(), new PvpCoreTest.Blocks(), new Random(seed));
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
        t.id = 1;
        return t;
    }

    /** Vanilla moveFlying on flat ground, roughly: keys and look direction to a step. */
    private static void move(PvpBrain.Self me, PvpBrain.Controls c) {
        me.yaw = c.yaw;
        me.pitch = c.pitch;
        me.slot = c.slot;
        double f = c.forward;
        double s = c.strafe;
        double len = Math.sqrt(f * f + s * s);
        if (len > 1) {
            f /= len;
            s /= len;
        }
        double speed = c.sprint && c.forward >= 0.8f ? 0.28 : 0.215;
        double yr = Math.toRadians(c.yaw);
        double mx = (s * Math.cos(yr) - f * Math.sin(yr)) * speed;
        double mz = (f * Math.cos(yr) + s * Math.sin(yr)) * speed;
        me.mx = mx;
        me.mz = mz;
        me.x += mx;
        me.z += mz;
    }

    private static double mean(int[] v) {
        double s = 0;
        for (int x : v) s += x;
        return s / v.length;
    }

    // ---- Tempo itself.

    @Test
    public void tempoIsSlowerOnEveryAxisAtLowerDifficulty() {
        PvpTempo prev = null;
        for (DifficultyMode m : LEVELS) {
            PvpTempo t = new PvpTempo(m.skill(0));
            if (prev != null) {
                assertTrue(m + " perception", t.perceptionTicks() < prev.perceptionTicks());
                assertTrue(m + " decisions", t.decisionTicks() < prev.decisionTicks());
                assertTrue(m + " first swing", t.firstSwingTicks() < prev.firstSwingTicks());
                assertTrue(m + " hit reaction", t.hitReactTicks() < prev.hitReactTicks());
                assertTrue(m + " dodge", t.dodgeReactTicks() < prev.dodgeReactTicks());
                assertTrue(m + " turn speed", t.maxTurnDeg() > prev.maxTurnDeg());
                assertTrue(m + " turn accel", t.turnAccelDeg() > prev.turnAccelDeg());
                assertTrue(m + " keys", t.inputRate() > prev.inputRate());
                assertTrue(m + " style shift", t.styleShiftRate() > prev.styleShiftRate());
            }
            prev = t;
        }
        // Expert stays within what a strong player does: not instant, not inhuman flicks.
        PvpTempo x = new PvpTempo(DifficultyMode.EXPERT.skill(0));
        assertTrue(x.perceptionTicks() >= 1.0);
        assertTrue(x.maxTurnDeg() <= 40f);
        assertTrue(x.firstSwingTicks() >= 2.0);
    }

    @Test
    public void naturalVariationStaysClose() {
        PvpTempo t = new PvpTempo(0.45);
        Random rng = new Random(3);
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < 2000; i++) {
            t.drift(rng);
            assertTrue(t.focus() >= 0.85 && t.focus() <= 1.15);
            int v = t.vary(10, rng, 1);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        assertTrue("never varies: " + lo + ".." + hi, hi > lo + 2);
        assertTrue("too wild: " + lo + ".." + hi, lo >= 5 && hi <= 15);
    }

    // ---- 1. Reactions and attack timing: a player walks into reach.

    /** Ticks from the player entering reach to the first swing. */
    private static int firstSwing(DifficultyMode m, long seed) {
        PvpBrain b = brain(m, seed);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(0.5, 9.0);
        int entry = -1;
        for (int i = 0; i < 300; i++) {
            t.z = Math.max(0.5 + 2.6, t.z - 0.13);
            if (entry < 0 && t.z - me.z <= PvpBrain.REACH) entry = i;
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            me.slot = c.slot;
            if (c.attack) {
                assertTrue(m + " swung before the player was in reach", entry >= 0);
                return i - entry;
            }
        }
        return 300;
    }

    @Test
    public void reactionAndAttackTimingFollowDifficulty() {
        double[] avg = new double[LEVELS.length];
        for (int k = 0; k < LEVELS.length; k++) {
            int[] d = new int[SEEDS];
            for (int s = 0; s < SEEDS; s++) d[s] = firstSwing(LEVELS[k], 1000 + s);
            avg[k] = mean(d);
        }
        String all = String.format("easy %.1f medium %.1f hard %.1f expert %.1f", avg[0], avg[1], avg[2], avg[3]);
        for (int k = 1; k < LEVELS.length; k++) assertTrue("not slower at lower difficulty: " + all, avg[k] < avg[k - 1]);
        assertTrue("easy not noticeably slow: " + all, avg[0] >= 10); // >= 0.5 s
        assertTrue("easy too passive: " + all, avg[0] <= 25);
        assertTrue("expert instant: " + all, avg[3] >= 2.5);
        assertTrue("expert sluggish: " + all, avg[3] <= 7);
    }

    // ---- 2. Turning: the player is behind it.

    private static int ticksToFace(DifficultyMode m, long seed) {
        PvpBrain b = brain(m, seed);
        PvpBrain.Self me = self(0.5, 0.5);
        me.yaw = 180f; // facing away (the player is at +z, yaw 0)
        PvpBrain.Target t = target(0.5, 3.2);
        // Let it see them first (perception lag), then count the turn.
        for (int i = 0; i < 120; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            if (Math.abs(PvpBrain.wrap(me.yaw)) < 10f) return i;
        }
        return 120;
    }

    @Test
    public void turningRespondsFasterAtHigherDifficulty() {
        double[] avg = new double[LEVELS.length];
        for (int k = 0; k < LEVELS.length; k++) {
            int[] d = new int[SEEDS];
            for (int s = 0; s < SEEDS; s++) d[s] = ticksToFace(LEVELS[k], 2000 + s);
            avg[k] = mean(d);
        }
        String all = String.format("easy %.1f medium %.1f hard %.1f expert %.1f", avg[0], avg[1], avg[2], avg[3]);
        for (int k = 1; k < LEVELS.length; k++) assertTrue("turn not faster: " + all, avg[k] < avg[k - 1]);
        // A 180 in under ~200 ms would be an inhuman flick (38 degrees a tick at most).
        assertTrue("expert turns 180 degrees inhumanly fast: " + all, avg[3] >= 4);
        assertTrue("easy barely slower to turn: " + all, avg[0] >= 2 * avg[3]);
        assertTrue("easy never turns: " + all, avg[0] < 40);
    }

    // ---- 3. Movement keys: a strafe reversal takes time on Easy.

    @Test
    public void movementKeysChangeAtTheDifficultysPace() {
        float[] maxStep = new float[LEVELS.length];
        for (int k = 0; k < LEVELS.length; k++) {
            PvpTempo tempo = new PvpTempo(LEVELS[k].skill(0));
            for (int s = 0; s < 10; s++) {
                PvpBrain b = brain(LEVELS[k], 3000 + s);
                PvpBrain.Self me = self(0.5, 0.5);
                PvpBrain.Target t = target(0.5, 3.0);
                float lastS = 0;
                float lastF = 0;
                for (int i = 0; i < 400; i++) {
                    // The player circles it slowly, so it keeps strafing and turning.
                    double a = i * 0.03;
                    t.x = me.x + Math.sin(a) * 3.0;
                    t.z = me.z + Math.cos(a) * 3.0;
                    PvpBrain.Controls c = b.tick(me, t);
                    if (c.mode == PvpBrain.Mode.MELEE || c.mode == PvpBrain.Mode.CHASE || c.mode == PvpBrain.Mode.RETREAT) {
                        float ds = Math.abs(c.strafe - lastS);
                        float df = Math.abs(c.forward - lastF);
                        // W-taps release W at once (a key release), so only strafe and W presses count.
                        if (c.forward >= lastF) maxStep[k] = Math.max(maxStep[k], df);
                        maxStep[k] = Math.max(maxStep[k], ds);
                        assertTrue(LEVELS[k] + " strafe jumped " + ds + " > " + tempo.inputRate(), ds <= tempo.inputRate() + 1e-3);
                    }
                    lastS = c.strafe;
                    lastF = c.forward;
                    move(me, c);
                    me.x = 0.5;
                    me.z = 0.5; // stay put: only the inputs are under test
                }
            }
        }
        assertTrue("easy keys as quick as expert", maxStep[0] < maxStep[3]);
    }

    // ---- 4. Decisions: the player backs off to mid range.

    private static int ticksToStopMelee(DifficultyMode m, long seed) {
        PvpBrain b = brain(m, seed);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(0.5, 3.0);
        for (int i = 0; i < 80; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
        }
        t.z = 12.0; // out of melee range in one step (knocked back, or ran)
        for (int i = 0; i < 200; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            if (c.mode != PvpBrain.Mode.MELEE) return i;
        }
        return 200;
    }

    @Test
    public void decisionsAreSlowerAtLowerDifficulty() {
        double[] avg = new double[LEVELS.length];
        for (int k = 0; k < LEVELS.length; k++) {
            int[] d = new int[SEEDS];
            for (int s = 0; s < SEEDS; s++) d[s] = ticksToStopMelee(LEVELS[k], 4000 + s);
            avg[k] = mean(d);
        }
        String all = String.format("easy %.1f medium %.1f hard %.1f expert %.1f", avg[0], avg[1], avg[2], avg[3]);
        for (int k = 1; k < LEVELS.length; k++) assertTrue("decisions not faster: " + all, avg[k] < avg[k - 1]);
        assertTrue("easy barely slower: " + all, avg[0] >= avg[3] + 6);
    }

    // ---- 5. Low health: defensive, inside the arena, still fighting.

    static final class FightLog {
        int ticks;
        int outside;
        int nearEdge;
        int fighting;
        int closeTicks;
        int swingsInReach;
        int modeChanges;
        int defending;
        int rangedUses;
        double minEdge = Double.MAX_VALUE;
        double moved;
        double recoverDefense = -1;
        int ticksToRecover = -1;
    }

    /**
     * A strong player chases the hurt opponent and hits it whenever in reach;
     * the opponent starts near the border. Its health stays low (no heals) for
     * the first part, then is restored to check it returns to normal play.
     */
    static FightLog lowHealthFight(DifficultyMode m, long seed, double startX) {
        PvpBrain b = brain(m, seed);
        double half = 24;
        b.setArena(-half, -half, half, half);
        PvpBrain.Self me = self(startX, 0.5);
        me.health = 5;
        // No blocks: this simulation has no jumping or placing, so a pillar would never finish.
        me.planks = 0;
        PvpBrain.Target t = target(startX + 3.5, 0.5);
        FightLog log = new FightLog();
        PvpBrain.Mode last = null;
        int hitTimer = 0;
        int lowPhase = 500;
        Random player = new Random(seed * 31);
        int backOff = 0;
        int landTimer = 0;
        for (int i = 0; i < lowPhase + 400; i++) {
            if (i == lowPhase) {
                me.health = 20;
                b.onTargetHealed(); // and it can tell the player isn't ahead any more
            }
            // The player rushes it and stays on it, but now and then backs off to mid range
            // for a moment (as players do), then comes again.
            double dx = me.x - t.x;
            double dz = me.z - t.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (backOff == 0 && player.nextDouble() < 0.01) backOff = 30 + player.nextInt(30);
            if (backOff > 0) {
                backOff--;
                t.sprinting = false;
                if (d < 7) {
                    t.x -= dx / d * 0.2;
                    t.z -= dz / d * 0.2;
                }
            } else if (d > 2.6) {
                t.sprinting = true;
                t.x += dx / d * 0.28;
                t.z += dz / d * 0.28;
            } else {
                t.sprinting = false;
            }
            t.x = Math.max(-half + 0.5, Math.min(half - 0.5, t.x));
            t.z = Math.max(-half + 0.5, Math.min(half - 0.5, t.z));
            t.yaw = (float) PvpBrain.yawTo(t.x, t.z, me.x, me.z);
            if (d < 3.0 && --hitTimer <= 0 && i < lowPhase) {
                hitTimer = 10;
                b.onHitTaken();
            }
            double px = me.x;
            double pz = me.z;
            PvpBrain.Controls c = b.tick(me, t);
            move(me, c);
            log.moved += Math.abs(me.x - px) + Math.abs(me.z - pz);
            if (c.attack && d <= PvpBrain.REACH + 0.35) {
                log.swingsInReach++;
                // Some of its swings land (the player's hurt time has to be over, and they
                // strafe and block); the player also eats a golden apple now and then.
                if (--landTimer <= 0 && player.nextDouble() < 0.4) {
                    landTimer = 10;
                    b.onHitLanded(false, 7);
                }
            }
            if (i % 150 == 149 && i < lowPhase) b.onTargetHealed();
            if (last != null && c.mode != last) log.modeChanges++;
            last = c.mode;
            if (i < lowPhase) {
                log.ticks++;
                double edge = b.edgeDistance(me.x, me.z);
                log.minEdge = Math.min(log.minEdge, edge);
                if (edge < 0) log.outside++;
                if (edge < 1.5) log.nearEdge++;
                if (c.mode != PvpBrain.Mode.CHASE) log.fighting++;
                if (c.mode == PvpBrain.Mode.RETREAT) log.defending++;
                if (c.use && (c.slot == PvpBrain.Slot.ROD || c.slot == PvpBrain.Slot.SNOWBALL)) log.rangedUses++;
                if (d < 9) log.closeTicks++;
            } else if (log.ticksToRecover < 0 && b.defense() <= 0.3
                    && (c.mode == PvpBrain.Mode.MELEE || c.mode == PvpBrain.Mode.CHASE)) {
                log.ticksToRecover = i - lowPhase;
            }
        }
        return log;
    }

    @Test
    public void lowHealthStaysInsideAndKeepsFightingAtEveryDifficulty() {
        for (DifficultyMode m : LEVELS) {
            for (int s = 0; s < 12; s++) {
                // Start right by the border (the old behaviour ran straight into it).
                FightLog log = lowHealthFight(m, 5000 + s, -21.0);
                String at = m + " seed " + s;
                assertTrue(at + " left the arena", log.outside == 0);
                assertTrue(at + " hugged the border " + log.nearEdge + " ticks", log.nearEdge < log.ticks / 20);
                assertTrue(at + " ran off: close " + log.closeTicks + "/" + log.ticks, log.closeTicks > log.ticks * 0.85);
                assertTrue(at + " never hit back", log.swingsInReach > 0);
                assertTrue(at + " never played defensively", log.defending > log.ticks / 4);
                assertTrue(at + " stood still: moved " + log.moved, log.moved > log.ticks * 0.08);
                // Not changing its mind all the time: under one mode change a second on average.
                assertTrue(at + " flip-flopped: " + log.modeChanges + " changes", log.modeChanges < (log.ticks + 400) / 20);
                assertTrue(at + " never went back to its normal game", log.ticksToRecover >= 0);
            }
        }
    }

    @Test
    public void lowerDifficultySwitchesBackMoreSlowly() {
        double easy = 0;
        double expert = 0;
        for (int s = 0; s < 12; s++) {
            easy += lowHealthFight(DifficultyMode.EASY, 6000 + s, 0.5).ticksToRecover;
            expert += lowHealthFight(DifficultyMode.EXPERT, 6000 + s, 0.5).ticksToRecover;
        }
        assertTrue("easy " + easy / 12 + " expert " + expert / 12, easy > expert);
    }

    @Test
    public void healthyOpponentDoesNotDefend() {
        PvpBrain b = brain(DifficultyMode.HARD, 7);
        PvpBrain.Self me = self(0.5, 0.5);
        PvpBrain.Target t = target(0.5, 3.0);
        for (int i = 0; i < 300; i++) {
            PvpBrain.Controls c = b.tick(me, t);
            me.yaw = c.yaw;
            me.pitch = c.pitch;
            assertTrue("defending at full health", c.mode != PvpBrain.Mode.RETREAT);
        }
    }
}
