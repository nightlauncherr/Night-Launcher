package com.kestrel.openmap;

import java.util.Random;

/**
 * How quickly the Open Map opponent perceives, decides, turns and moves at
 * its difficulty. Lower difficulties are slower in all of these together, so
 * the whole opponent feels slower rather than just weaker; higher ones are
 * quicker, but never faster than a strong player.
 *
 * <p>{@code level} is 0..1 (Easy ~0.15, Medium ~0.45, Hard ~0.7, Expert ~0.95).
 * Every value is a base; callers add natural variation through {@link #vary}
 * and the slowly drifting {@link #focus()}.
 */
final class PvpTempo {
    private final double level;
    /** Slowly wandering attentiveness (0.85 sharp .. 1.15 slow): delays scale with it. */
    private double focus = 1.0;

    PvpTempo(double level) {
        this.level = level < 0 ? 0 : (level > 1 ? 1 : level);
    }

    double level() {
        return level;
    }

    private double lerp(double slow, double fast) {
        return slow + (fast - slow) * level;
    }

    /** Perception lag in ticks: ~350 ms on Easy down to ~50-100 ms on Expert. */
    double perceptionTicks() {
        return lerp(7.6, 1.2);
    }

    /** Ticks between reconsidering the plan: ~0.5 s on Easy down to ~0.1 s on Expert. */
    double decisionTicks() {
        return lerp(10.5, 1.8);
    }

    /** Ticks to react to being hit (stop what it's doing, rethink). */
    double hitReactTicks() {
        return lerp(5.5, 0.5);
    }

    /** Fastest turn per tick (degrees). */
    float maxTurnDeg() {
        return (float) lerp(16.0, 38.0);
    }

    /** How quickly the turn speed builds up (degrees per tick, per tick): sluggish on Easy. */
    float turnAccelDeg() {
        return (float) lerp(4.0, 32.0);
    }

    /** How far the movement keys can change per tick (1.4 is a full strafe reversal). */
    float inputRate() {
        return (float) lerp(0.3, 1.4);
    }

    /** Ticks from "in reach" to the first swing (before perception lag). */
    double firstSwingTicks() {
        return lerp(7.0, 2.3);
    }

    /** Strafe-switch interval multiplier: slower changes of direction on Easy. */
    double strafeIntervalMul() {
        return lerp(1.4, 0.85);
    }

    /** Extra ticks before side-stepping a projectile it has noticed. */
    double dodgeReactTicks() {
        return lerp(7.0, 2.0);
    }

    /** Per-tick rate at which it shifts between attacking and defending (1 / ticks). */
    double styleShiftRate() {
        return 1.0 / lerp(30.0, 6.0);
    }

    /** Ticks between path replans while chasing. */
    double replanTicks() {
        return lerp(11.0, 4.0);
    }

    /** Extra ticks between block placements (deciding where the next one goes). */
    double placeGapTicks() {
        return lerp(2.0, 0.0);
    }

    double focus() {
        return focus;
    }

    /** One tick of attention drifting (a random walk that is pulled back toward 1). */
    void drift(Random rng) {
        focus += (rng.nextDouble() - 0.5) * 0.02 + (1.0 - focus) * 0.01;
        if (focus < 0.85) focus = 0.85;
        if (focus > 1.15) focus = 1.15;
    }

    /**
     * A delay in whole ticks: the base scaled by focus, with about +-25% natural
     * variation, never below {@code min}.
     */
    int vary(double base, Random rng, int min) {
        double v = base * focus * (0.75 + rng.nextDouble() * 0.5);
        int r = (int) Math.round(v);
        return r < min ? min : r;
    }
}
