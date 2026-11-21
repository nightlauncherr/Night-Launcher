package com.kestrel.gui.settings;

/**
 * Frame-rate independent easing for the settings screen. Pure (no Minecraft
 * types) so it is unit-tested. Values move toward a target with an
 * exponential approach, so animations look the same at 30 and 300 FPS.
 */
public final class Anim {
    private Anim() {
    }

    /**
     * Move {@code current} toward {@code target}; {@code rate} is how much of the
     * remaining distance is covered per second (about 1 - e^-rate).
     */
    public static float approach(float current, float target, float rate, float dtSeconds) {
        if (dtSeconds <= 0) return current;
        float k = 1f - (float) Math.exp(-rate * dtSeconds);
        float v = current + (target - current) * k;
        return Math.abs(target - v) < 0.002f ? target : v;
    }

    /** Linear progress from 0 to 1 over {@code durationMs}, clamped. */
    public static float progress(long startMs, long nowMs, long durationMs) {
        if (durationMs <= 0) return 1f;
        float t = (nowMs - startMs) / (float) durationMs;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    /** Cubic ease-out. */
    public static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Blend two ARGB colours channel by channel. */
    public static int mix(int a, int b, float t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    /** The colour with its alpha multiplied by {@code f}. */
    public static int fade(int argb, float f) {
        f = f < 0 ? 0 : (f > 1 ? 1 : f);
        int a = Math.round((argb >>> 24) * f);
        return a << 24 | (argb & 0xFFFFFF);
    }
}
