package com.kestrel.animation;

/**
 * First-person swing styles and the poses they produce. Pure (only
 * {@link Ops}), so every pose is unit-tested and the same maths drives both
 * OpenGL and the slash-trail sampling.
 *
 * <p>All styles are visual only: they change where the held item is drawn in
 * first person, never when or whether an attack happens.
 */
public enum SwingStyle {
    /** Minecraft's own animation, untouched. */
    VANILLA("vanilla", "Vanilla"),
    /** Minecraft's motion, but timed in real time and eased so it never jerks at the start. */
    SMOOTH("smooth", "Smooth"),
    /** Kestrel's slash: wind-up, sweeping arc, follow-through, glowing trail. */
    KESTREL("kestrel", "Night slash"),
    /** A heavier overhead chop (wind-up high, strike down), suited to axes and tools. */
    CHOP("chop", "Chop");

    public final String id;
    public final String label;

    SwingStyle(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static SwingStyle byId(String id, SwingStyle fallback) {
        for (SwingStyle s : values()) {
            if (s.id.equals(id)) return s;
        }
        return fallback;
    }

    // Pose channels: translation x, y, z (first-person units) and rotations
    // about the view's z (screen plane), x and y axes, in degrees.
    public static final int TX = 0, TY = 1, TZ = 2, RZ = 3, RX = 4, RY = 5, CHANNELS = 6;

    /**
     * The Kestrel slash. Keys: rest, wind-up (blade cocked back and raised),
     * strike (fast sweep across and down), follow-through (carries a little
     * further and slows), settle back to rest.
     */
    static final SwingCurve SLASH = new SwingCurve(
            new float[] {0f, 0.14f, 0.38f, 0.52f, 0.68f, 1f},
            new float[][] {
                    {0f, -0.08f, -0.30f, -0.30f, -0.14f, 0f}, // TX
                    {0f, 0.14f, -0.02f, -0.28f, -0.56f, 0f},  // TY: dips out of view, rises back
                    {0f, 0f, -0.05f, -0.05f, 0f, 0f},         // TZ
                    {0f, -25f, 140f, 175f, 320f, 360f},       // RZ: one full turn, never rewinds
                    {0f, -10f, 10f, 12f, 5f, 0f},             // RX: tilt into the cut
                    {0f, 70f, 80f, 80f, 55f, 0f},             // RY: blade turned to face you
            });

    /** Overhead chop for tools. */
    static final SwingCurve CHOP_CURVE = new SwingCurve(
            new float[] {0f, 0.2f, 0.46f, 0.6f, 1f},
            new float[][] {
                    {0f, 0.02f, -0.06f, -0.06f, 0f},  // TX
                    {0f, 0.10f, -0.18f, -0.30f, 0f},  // TY: lift, then drive down
                    {0f, 0.04f, -0.10f, -0.08f, 0f},  // TZ
                    {0f, -6f, 8f, 10f, 0f},           // RZ
                    {0f, -25f, -125f, -130f, 0f},     // RX: head raised back, then struck forward and down
                    {0f, 4f, -6f, -8f, 0f},           // RY
            });

    /** The part of the swing (in normalised time) that leaves a trail. */
    public static final float TRAIL_START = 0.12f, TRAIL_END = 0.56f;

    /**
     * Pose channels at swing progress t for this style, scaled by intensity.
     * VANILLA and SMOOTH are not keyframed and return zeros (see
     * {@link #apply}).
     */
    public void pose(float t, float intensity, float[] out) {
        SwingCurve c = this == KESTREL ? SLASH : (this == CHOP ? CHOP_CURVE : null);
        if (c == null) {
            for (int i = 0; i < CHANNELS; i++) out[i] = 0f;
            return;
        }
        c.sample(t, out);
        for (int i = 0; i < CHANNELS; i++) out[i] *= intensity;
    }

    /**
     * Place the held item, replacing vanilla's {@code transformFirstPersonItem}
     * plus {@code doItemUsedTransformations} (and {@code doBlockTransformations}
     * when {@code block} &gt; 0).
     *
     * @param equip     vanilla's equip progress (0 = fully raised, 1 = lowered)
     * @param t         swing progress in [0, 1] (real-time based)
     * @param pose      channels from {@link #pose} (keyframed styles), possibly blended
     * @param block     how far into the blocking pose, 0..1
     * @param sway      idle sway offsets {x, y}
     * @param intensity strength of the SMOOTH motion
     */
    public void apply(Ops ops, float equip, float t, float[] pose, float block, float[] sway, float intensity) {
        if (this == SMOOTH) {
            // Vanilla's doItemUsedTransformations, with an eased progress:
            // vanilla uses sqrt(p), whose infinite slope at 0 makes the swing jump.
            float e = easeOutQuad(t);
            float f = (float) Math.sin(e * Math.PI) * intensity;
            float f2 = (float) Math.sin(t * Math.PI) * intensity;
            ops.translate(-0.4f * f + sway[0], 0.2f * (float) Math.sin(e * Math.PI * 2) * intensity + sway[1], -0.2f * f2);
            ops.translate(0.56f, -0.52f, -0.72f);
            ops.translate(0f, equip * -0.6f, 0f);
            ops.rotate(45f, 0f, 1f, 0f);
            float g = (float) Math.sin(t * t * Math.PI) * intensity;
            ops.rotate(g * -20f, 0f, 1f, 0f);
            ops.rotate(f * -20f, 0f, 0f, 1f);
            ops.rotate(f * -80f, 1f, 0f, 0f);
        } else {
            ops.translate(0.56f + pose[TX] + sway[0], -0.52f + pose[TY] + sway[1], -0.72f + pose[TZ]);
            ops.translate(0f, equip * -0.6f, 0f);
            ops.rotate(pose[RZ], 0f, 0f, 1f);
            ops.rotate(pose[RX], 1f, 0f, 0f);
            ops.rotate(pose[RY], 0f, 1f, 0f);
            ops.rotate(45f, 0f, 1f, 0f);
        }
        ops.scale(0.4f, 0.4f, 0.4f);
        if (block > 0f) {
            // Vanilla's doBlockTransformations, scaled by how far into the block we are.
            ops.translate(-0.5f * block, 0.2f * block, 0f);
            ops.rotate(30f * block, 0f, 1f, 0f);
            ops.rotate(-80f * block, 1f, 0f, 0f);
            ops.rotate(60f * block, 0f, 1f, 0f);
        }
    }

    /** Vanilla's transformFirstPersonItem, exactly (used to ease the equip motion of other items). */
    public static void vanillaTransform(Ops ops, float equip, float swing) {
        ops.translate(0.56f, -0.52f, -0.72f);
        ops.translate(0f, equip * -0.6f, 0f);
        ops.rotate(45f, 0f, 1f, 0f);
        float f = (float) Math.sin(swing * swing * Math.PI);
        float f1 = (float) Math.sin(Math.sqrt(swing) * Math.PI);
        ops.rotate(f * -20f, 0f, 1f, 0f);
        ops.rotate(f1 * -20f, 0f, 0f, 1f);
        ops.rotate(f1 * -80f, 1f, 0f, 0f);
        ops.scale(0.4f, 0.4f, 0.4f);
    }

    static float easeOutQuad(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u;
    }

    /** Smoothstep easing of the equip (item switch) motion. */
    public static float easeEquip(float equip) {
        float e = clamp01(equip);
        return e * e * (3f - 2f * e);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
