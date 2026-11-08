package com.kestrel.sumo;

/**
 * How the opponent likes to fight. Each style only shifts the brain's existing
 * choices (range, pressure, strafing, jumping, edge care); none of them change
 * reach, click speed limits or knockback.
 */
public enum Playstyle {
    BALANCED("Balanced", "Mixes pressure, strafing and spacing.", 0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
    AGGRESSIVE("Aggressive", "Rushes in and keeps pushing, takes more risks near edges.", 0.25, -0.2, -0.15, 1.5, 0.7, 1.0),
    DEFENSIVE("Defensive", "Keeps its distance, guards the edge and punishes mistakes.", -0.25, 0.2, 0.2, 0.6, 1.0, 1.2),
    STRAFER("Strafer", "Circles constantly and switches direction often.", 0.0, 0.0, 0.0, 0.8, 1.3, 0.6);

    public final String label;
    public final String description;
    /** Added to how hard it walks in. */
    final double approachBias;
    /** Added to its preferred hitting range (blocks). */
    final double rangeOffset;
    /** Added to edge caution (0..1 after clamping). */
    final double cautionOffset;
    final double jumpMul;
    final double strafeMul;
    /** Multiplies how long it keeps one strafe direction. */
    final double strafeTimeMul;

    Playstyle(String label, String description, double approachBias, double rangeOffset, double cautionOffset,
              double jumpMul, double strafeMul, double strafeTimeMul) {
        this.label = label;
        this.description = description;
        this.approachBias = approachBias;
        this.rangeOffset = rangeOffset;
        this.cautionOffset = cautionOffset;
        this.jumpMul = jumpMul;
        this.strafeMul = strafeMul;
        this.strafeTimeMul = strafeTimeMul;
    }

    public static Playstyle parse(String s) {
        if (s == null) return null;
        for (Playstyle p : values()) {
            if (p.name().equalsIgnoreCase(s.trim()) || p.label.equalsIgnoreCase(s.trim())) return p;
        }
        return null;
    }

    public Playstyle next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
