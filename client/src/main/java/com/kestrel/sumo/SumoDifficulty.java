package com.kestrel.sumo;

/**
 * Adaptive difficulty of the Sumo opponent. One skill value (0..1) maps to
 * human-scale limits: how late it reacts, how precisely and how fast it aims,
 * how fast it clicks, how reliably it sprint-resets, how carefully it guards
 * the edge and how much it uses what it learned about the player. Even at
 * full skill these stay within what a strong player can do; reach and
 * knockback are never changed (they are the vanilla rules in the entity).
 */
public final class SumoDifficulty {
    public static final double START = 0.35;
    public static final double MIN = 0.05;
    public static final double MAX = 1.0;

    private final double skill;

    public SumoDifficulty(double skill) {
        this.skill = clamp(skill);
    }

    public double skill() {
        return skill;
    }

    /** Ticks between seeing something and acting on it (300 ms down to 50 ms). */
    public int reactionTicks() {
        return (int) Math.round(lerp(6, 1));
    }

    /** Size of the wandering aim error (degrees). */
    public double aimNoiseDeg() {
        return lerp(9.0, 1.5);
    }

    /** Fastest turn per tick (degrees); a fast human flick is around 40. */
    public float maxTurnDeg() {
        return (float) lerp(18.0, 40.0);
    }

    /** Average clicks per second. */
    public double cps() {
        return lerp(6.0, 11.0);
    }

    /** Chance of a clean W-tap (sprint reset) right after landing a sprint hit. */
    public double wtapChance() {
        return lerp(0.25, 0.95);
    }

    /** 0..1: how strongly it keeps away from the edge itself. */
    public double edgeCaution() {
        return lerp(0.5, 1.0);
    }

    /** 0..1: how much it uses the learned player profile. */
    public double adaptWeight() {
        return lerp(0.2, 1.0);
    }

    /** Ticks of target movement it leads its aim by. */
    public double leadTicks() {
        return lerp(0.5, 2.5);
    }

    /**
     * Skill after a match. Player wins make it harder (more after a streak),
     * losses make it easier; draws change nothing.
     *
     * @param playerWinStreak the player's win streak including this match (0 if it was not a win)
     */
    public static double afterMatch(double skill, SumoStats.Result result, int playerWinStreak) {
        double next = skill;
        if (result == SumoStats.Result.WIN) {
            next = skill + 0.05 + 0.01 * Math.min(Math.max(playerWinStreak - 1, 0), 5);
        } else if (result == SumoStats.Result.LOSS) {
            next = skill - 0.04;
        }
        return clamp(next);
    }

    static double clamp(double v) {
        if (Double.isNaN(v)) return START;
        return v < MIN ? MIN : (v > MAX ? MAX : v);
    }

    private double lerp(double easy, double hard) {
        return easy + (hard - easy) * skill;
    }
}
