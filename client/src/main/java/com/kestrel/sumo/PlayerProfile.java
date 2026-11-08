package com.kestrel.sumo;

/**
 * What the opponent has learned about how the player fights, kept across
 * matches (saved inside {@link SumoStats}). Plain fields so Gson can store it.
 *
 * <p>Lateral directions are from the opponent's point of view: +1 = the
 * player moving to the opponent's left, -1 = to its right.
 */
public final class PlayerProfile {
    /** Ticks seen strafing left / right. */
    public long leftTicks;
    public long rightTicks;
    /** Average length of one strafe in one direction (ticks), smoothed. */
    public double avgStrafeRun;
    /** Number of strafe runs seen (how much {@link #avgStrafeRun} can be trusted). */
    public long strafeRuns;
    /** Jumps seen and fighting ticks observed. */
    public long jumps;
    public long observedTicks;
    /** Ticks the player spent close to an edge. */
    public long edgeTicks;
    /** Average distance the player lands hits from, smoothed. */
    public double avgHitRange;
    public long hitRangeSamples;

    private static final double RUN_SMOOTHING = 0.12;
    private static final double RANGE_SMOOTHING = 0.1;

    /** A finished strafe run of {@code ticks} in one direction. */
    public void recordStrafeRun(int ticks) {
        if (ticks <= 0) return;
        avgStrafeRun = strafeRuns == 0 ? ticks : avgStrafeRun + (ticks - avgStrafeRun) * RUN_SMOOTHING;
        strafeRuns++;
    }

    public void recordLateralTick(int dir) {
        if (dir > 0) leftTicks++;
        else if (dir < 0) rightTicks++;
    }

    public void recordTick(boolean jumped, boolean nearEdge) {
        observedTicks++;
        if (jumped) jumps++;
        if (nearEdge) edgeTicks++;
    }

    public void recordHitRange(double range) {
        if (!(range > 0) || range > 6) return;
        avgHitRange = hitRangeSamples == 0 ? range : avgHitRange + (range - avgHitRange) * RANGE_SMOOTHING;
        hitRangeSamples++;
    }

    /** -1..1: preferred strafe side (+ = left), 0 when unknown. */
    public double preferredLateral() {
        long total = leftTicks + rightTicks;
        if (total < 100) return 0;
        return (leftTicks - rightTicks) / (double) total;
    }

    /** Typical strafe run length (ticks), or 0 while still unknown. */
    public double expectedStrafeRun() {
        return strafeRuns >= 6 ? avgStrafeRun : 0;
    }

    /** Jumps per second while fighting. */
    public double jumpsPerSecond() {
        return observedTicks < 200 ? 0 : jumps * 20.0 / observedTicks;
    }

    /** 0..1: share of fighting time spent near an edge. */
    public double edgeHabit() {
        return observedTicks < 200 ? 0 : edgeTicks / (double) observedTicks;
    }

    /** Typical hitting range, or 0 while still unknown. */
    public double typicalHitRange() {
        return hitRangeSamples >= 5 ? avgHitRange : 0;
    }

    /** Repair values that a hand-edited or damaged file could contain. */
    void normalize() {
        leftTicks = Math.max(0, leftTicks);
        rightTicks = Math.max(0, rightTicks);
        strafeRuns = Math.max(0, strafeRuns);
        jumps = Math.max(0, jumps);
        observedTicks = Math.max(0, observedTicks);
        edgeTicks = Math.max(0, Math.min(edgeTicks, observedTicks));
        jumps = Math.min(jumps, observedTicks);
        hitRangeSamples = Math.max(0, hitRangeSamples);
        if (!(avgStrafeRun >= 0) || avgStrafeRun > 400) avgStrafeRun = 0;
        if (!(avgHitRange >= 0) || avgHitRange > 6) avgHitRange = 0;
    }
}
