package com.kestrel.openmap;

/**
 * What the opponent has learned about the player's Open Map habits, kept
 * across matches. Every value is a slowly moving average (0..1 unless noted),
 * built only from what the opponent could see in the fights: how the player
 * moved, jumped, used items and built.
 */
public final class PvpProfile {
    /** Of the player's sideways movement in close combat, the share to their left. */
    public double strafeLeft = 0.5;
    /** Jumps per second in close combat (0..3). */
    public double jumpRate = 0.5;
    /** Rod casts per minute (0..30). */
    public double rodRate = 2;
    /** Snowballs thrown per minute (0..60). */
    public double snowballRate = 2;
    /** Blocks placed per minute (0..60). */
    public double buildRate = 2;
    /** Share of mid-range time spent closing in (rushing) rather than holding back. */
    public double rush = 0.5;
    /** Share of time backing off after taking a run of hits. */
    public double retreat = 0.3;
    /** Of the player's sprint hits, the share followed by a sprint reset. */
    public double sprintReset = 0.4;
    /** Matches observed. */
    public int samples;

    /** The averages after one more match (weight shrinks to 0.25 after a few matches). */
    public void learn(Observation o) {
        if (o == null || o.ticks < 40) return;
        double w = Math.max(0.25, 1.0 / (samples + 2));
        double minutes = o.ticks / 1200.0;
        if (o.strafeTicks >= 20) strafeLeft = mix(strafeLeft, o.strafeLeftTicks / (double) o.strafeTicks, w);
        if (o.closeTicks >= 20) jumpRate = mix(jumpRate, clamp(o.closeJumps / (o.closeTicks / 20.0), 0, 3), w);
        rodRate = mix(rodRate, clamp(o.rodCasts / minutes, 0, 30), w);
        snowballRate = mix(snowballRate, clamp(o.snowballs / minutes, 0, 60), w);
        buildRate = mix(buildRate, clamp(o.blocksPlaced / minutes, 0, 60), w);
        if (o.midTicks >= 20) rush = mix(rush, o.approachTicks / (double) o.midTicks, w);
        if (o.pressuredTicks >= 20) retreat = mix(retreat, o.retreatTicks / (double) o.pressuredTicks, w);
        if (o.sprintHits >= 3) sprintReset = mix(sprintReset, o.sprintResets / (double) o.sprintHits, w);
        samples++;
        normalize();
    }

    public void normalize() {
        strafeLeft = clamp(nan(strafeLeft, 0.5), 0, 1);
        jumpRate = clamp(nan(jumpRate, 0.5), 0, 3);
        rodRate = clamp(nan(rodRate, 2), 0, 30);
        snowballRate = clamp(nan(snowballRate, 2), 0, 60);
        buildRate = clamp(nan(buildRate, 2), 0, 60);
        rush = clamp(nan(rush, 0.5), 0, 1);
        retreat = clamp(nan(retreat, 0.3), 0, 1);
        sprintReset = clamp(nan(sprintReset, 0.4), 0, 1);
        if (samples < 0) samples = 0;
    }

    /** Counts gathered during one match. */
    public static final class Observation {
        public int ticks;
        public int strafeTicks;
        public int strafeLeftTicks;
        public int closeTicks;
        public int closeJumps;
        public int rodCasts;
        public int snowballs;
        public int blocksPlaced;
        public int midTicks;
        public int approachTicks;
        public int pressuredTicks;
        public int retreatTicks;
        public int sprintHits;
        public int sprintResets;
    }

    private static double mix(double old, double now, double w) {
        return old + (now - old) * w;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double nan(double v, double d) {
        return Double.isNaN(v) || Double.isInfinite(v) ? d : v;
    }
}
