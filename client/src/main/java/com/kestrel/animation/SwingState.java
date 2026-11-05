package com.kestrel.animation;

/**
 * Real-time timeline of the first-person swing. Pure (time is passed in), so
 * it is unit-tested.
 *
 * <p>A swing is started by the game's own swing (the same moment vanilla's arm
 * starts moving), so the animation is synchronised with attack input but never
 * delays or changes it. Progress runs on wall-clock time, so it looks the same
 * at 30 or 300 FPS. When a new swing starts before the previous one has
 * finished (spam clicking), the pose is cross-faded from where it was for a
 * few frames instead of snapping back to the start.
 */
public final class SwingState {
    /** Vanilla's swing lasts 6 ticks (0.3 s). */
    public static final float BASE_DURATION = 0.30f;
    /** Cross-fade length when a swing restarts mid-way. */
    static final float BLEND_SECONDS = 0.07f;
    /** Rate (per second) at which the block pose eases in and out. */
    static final float BLOCK_RATE = 18f;
    static final float MAX_DT = 0.05f;

    private long startNanos = Long.MIN_VALUE;
    private long blendStartNanos = Long.MIN_VALUE;
    private final float[] blendFrom = new float[SwingStyle.CHANNELS];
    private float block;
    private long lastNanos = Long.MIN_VALUE;

    /** A swing started (vanilla's arm swing began, or a visual swing while blocking). */
    public void startSwing(long nowNanos, float[] currentPose) {
        if (isSwinging(nowNanos, BASE_DURATION * 4)) {
            System.arraycopy(currentPose, 0, blendFrom, 0, blendFrom.length);
            blendStartNanos = nowNanos;
        }
        startNanos = nowNanos;
    }

    /** Swing progress in [0, 1]; 1 when no swing is running. */
    public float progress(long nowNanos, float duration) {
        if (startNanos == Long.MIN_VALUE) return 1f;
        float t = (nowNanos - startNanos) / 1.0e9f / Math.max(0.05f, duration);
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    public boolean isSwinging(long nowNanos, float duration) {
        return progress(nowNanos, duration) < 1f;
    }

    /** Seconds since the swing started (for the trail, which outlives the swing a little). */
    public float secondsSinceStart(long nowNanos) {
        return startNanos == Long.MIN_VALUE ? Float.MAX_VALUE : (nowNanos - startNanos) / 1.0e9f;
    }

    /**
     * Blend the freshly computed pose with the pose at the moment of a
     * restart, fading over {@link #BLEND_SECONDS}. Modifies pose in place.
     */
    public void blend(long nowNanos, float[] pose) {
        if (blendStartNanos == Long.MIN_VALUE) return;
        float u = (nowNanos - blendStartNanos) / 1.0e9f / BLEND_SECONDS;
        if (u >= 1f) {
            blendStartNanos = Long.MIN_VALUE;
            return;
        }
        if (u < 0f) u = 0f;
        float w = 1f - u * u * (3f - 2f * u); // weight of the old pose, smoothstep down
        for (int i = 0; i < pose.length; i++) {
            float d = blendFrom[i] - pose[i];
            if (i >= SwingStyle.RZ) {
                // Angles: take the short way round (a swing ends at 360 = its start at 0).
                d -= 360f * Math.round(d / 360f);
            }
            pose[i] += d * w;
        }
    }

    /** Ease the block pose towards on/off; returns the current amount 0..1. */
    public float updateBlock(long nowNanos, boolean blocking, boolean smooth) {
        float dt = lastNanos == Long.MIN_VALUE ? 0f : (nowNanos - lastNanos) / 1.0e9f;
        lastNanos = nowNanos;
        float target = blocking ? 1f : 0f;
        if (!smooth) {
            block = target;
        } else if (dt > 0f) {
            float k = (float) (1.0 - Math.exp(-BLOCK_RATE * Math.min(dt, MAX_DT)));
            block += (target - block) * k;
            if (Math.abs(target - block) < 0.002f) block = target;
        }
        return block;
    }

    /** Gentle idle sway offsets {x, y} (a slow figure eight), scaled by amount. */
    public static void sway(long nowNanos, float amount, float[] out) {
        double s = nowNanos / 1.0e9;
        out[0] = (float) (Math.sin(s * 1.1) * 0.006 * amount);
        out[1] = (float) (Math.sin(s * 2.2) * 0.004 * amount);
    }

    /** Forget everything (world change, item change to something not animated). */
    public void reset() {
        startNanos = Long.MIN_VALUE;
        blendStartNanos = Long.MIN_VALUE;
        block = 0f;
        lastNanos = Long.MIN_VALUE;
    }
}
