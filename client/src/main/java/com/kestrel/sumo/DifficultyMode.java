package com.kestrel.sumo;

/**
 * Adaptive (follows the player's results, the default) or a fixed level. A
 * fixed level never changes the saved adaptive progress.
 */
public enum DifficultyMode {
    ADAPTIVE("Adaptive", -1),
    EASY("Easy", 0.15),
    MEDIUM("Medium", 0.45),
    HARD("Hard", 0.7),
    EXPERT("Expert", 0.95);

    public final String label;
    private final double fixedSkill;

    DifficultyMode(String label, double fixedSkill) {
        this.label = label;
        this.fixedSkill = fixedSkill;
    }

    /** The skill to play at, given the saved adaptive skill. */
    public double skill(double adaptive) {
        return fixedSkill < 0 ? SumoDifficulty.clamp(adaptive) : fixedSkill;
    }

    public boolean adaptive() {
        return fixedSkill < 0;
    }

    public static DifficultyMode parse(String s) {
        if (s == null) return null;
        for (DifficultyMode m : values()) {
            if (m.name().equalsIgnoreCase(s.trim()) || m.label.equalsIgnoreCase(s.trim())) return m;
        }
        return null;
    }

    public DifficultyMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
