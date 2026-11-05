package com.kestrel.animation;

/** Picks a trail colour from an item's pixels. Pure, unit-tested. */
public final class TrailColor {
    /** Used for grey blades (iron, stone): a cool steel glow. */
    static final int STEEL = 0xFFCFE6FF;

    private TrailColor() {
    }

    /**
     * Saturation-weighted average of the opaque pixels, then made bright, so a
     * diamond sword glows cyan and a gold sword yellow even though most of
     * their pixels are dark outline or handle.
     */
    public static int fromPixels(int[] argb) {
        double r = 0, g = 0, b = 0, wsum = 0;
        for (int p : argb) {
            int a = (p >>> 24) & 0xFF;
            if (a < 128) continue;
            float pr = ((p >> 16) & 0xFF) / 255f;
            float pg = ((p >> 8) & 0xFF) / 255f;
            float pb = (p & 0xFF) / 255f;
            float max = Math.max(pr, Math.max(pg, pb));
            float min = Math.min(pr, Math.min(pg, pb));
            float sat = max <= 0f ? 0f : (max - min) / max;
            double w = sat * sat * max + 1e-4;
            r += pr * w;
            g += pg * w;
            b += pb * w;
            wsum += w;
        }
        if (wsum <= 0) return STEEL;
        r /= wsum;
        g /= wsum;
        b /= wsum;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        if (max <= 0.05 || (max - min) / max < 0.18) return STEEL;
        // Normalise brightness: the brightest channel becomes 1.
        int ir = (int) Math.round(Math.min(1.0, r / max) * 255);
        int ig = (int) Math.round(Math.min(1.0, g / max) * 255);
        int ib = (int) Math.round(Math.min(1.0, b / max) * 255);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }
}
