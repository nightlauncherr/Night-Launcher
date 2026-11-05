package com.kestrel.combat;

/** Strength of the Hit Color tint over time. Pure, so the curve is unit-tested. */
public final class HitTint {
    private HitTint() {
    }

    /**
     * @param ageMs       time since the confirmed hit
     * @param durationMs  how long the tint lasts
     * @param intensity   peak strength (0..1)
     * @param fade        fade out over the duration; otherwise hold, then stop
     * @return tint strength in 0..intensity (0 = no tint)
     */
    public static float strength(long ageMs, long durationMs, float intensity, boolean fade) {
        if (ageMs < 0 || durationMs <= 0 || ageMs >= durationMs) return 0f;
        if (!fade) return intensity;
        float t = ageMs / (float) durationMs;
        // Ease out: strong at first, gentle tail.
        float k = 1f - t;
        return intensity * k * k;
    }
}
