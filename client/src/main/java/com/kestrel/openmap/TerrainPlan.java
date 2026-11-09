package com.kestrel.openmap;

/**
 * Works out a flatter version of an existing heightmap (the top ground block of
 * each column) while keeping its character: where the old map had its big
 * hills, the new one has gentle rises a few blocks high; where it had valleys,
 * shallow dips; most of it becomes open flat grassland. Steps are never more
 * than one block between neighbouring columns (no cliffs). Fixed columns
 * (the lobby, anything a player built) keep their height exactly, and the
 * ground around the lobby levels out to meet it.
 *
 * <p>Pure computation on arrays, so it can be checked outside the game.
 */
public final class TerrainPlan {
    /** Height of the big rises and dips, at most this many blocks from the base. */
    static final double HILL_AMPLITUDE = 3.4;
    /** Large features below this (in blocks) flatten out completely: wide flat areas. */
    static final double FLAT_DEADZONE = 1.1;
    /** Radius (columns) of the smoothing that keeps only the map's big shapes. */
    static final int SHAPE_RADIUS = 18;

    private TerrainPlan() {
    }

    /**
     * @param w      columns along x
     * @param d      columns along z
     * @param height current top-ground height per column ({@code x + z * w})
     * @param fixed  columns that must keep their height (may be null)
     * @param base   the level the flat ground settles at
     * @param levelX x of a spot to level the ground around (the lobby), or -1
     * @param levelZ z of that spot
     * @param levelRadius how far around it the ground is levelled to {@code base}
     * @return new top-ground height per column
     */
    public static int[] plan(int w, int d, int[] height, boolean[] fixed, int base, int levelX, int levelZ, int levelRadius) {
        int n = w * d;
        double[] blur = boxBlur(w, d, height, SHAPE_RADIUS);
        double mean = 0;
        for (double v : blur) mean += v;
        mean /= n;
        double var = 0;
        for (double v : blur) var += (v - mean) * (v - mean);
        double std = Math.max(1.0, Math.sqrt(var / n));
        int[] out = new int[n];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int i = x + z * w;
                if (fixed != null && fixed[i]) {
                    out[i] = height[i];
                    continue;
                }
                double hill = HILL_AMPLITUDE * Math.tanh((blur[i] - mean) / (std * 1.2));
                if (Math.abs(hill) < FLAT_DEADZONE) hill = 0;
                else hill -= Math.signum(hill) * FLAT_DEADZONE * 0.6;
                double detail = Math.max(-0.6, Math.min(0.6, (height[i] - blur[i]) * 0.08));
                double t = base + hill + detail;
                if (levelX >= 0) {
                    double dx = x - levelX;
                    double dz = z - levelZ;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= levelRadius) t = base;
                    else if (dist <= levelRadius * 2.5) {
                        double f = (dist - levelRadius) / (levelRadius * 1.5);
                        t = base + (t - base) * f;
                    }
                }
                out[i] = (int) Math.round(t);
            }
        }
        relaxSlopes(w, d, out, fixed);
        return out;
    }

    /** No step bigger than one block between neighbours; fixed columns never move. */
    static void relaxSlopes(int w, int d, int[] h, boolean[] fixed) {
        for (int pass = 0; pass < 200; pass++) {
            boolean changed = false;
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    int i = x + z * w;
                    for (int k = 0; k < 2; k++) {
                        int nx = k == 0 ? x + 1 : x;
                        int nz = k == 0 ? z : z + 1;
                        if (nx >= w || nz >= d) continue;
                        int j = nx + nz * w;
                        int diff = h[i] - h[j];
                        if (Math.abs(diff) <= 1) continue;
                        boolean fi = fixed != null && fixed[i];
                        boolean fj = fixed != null && fixed[j];
                        if (fi && fj) continue;
                        // Move the higher one down (or the free one toward the fixed one).
                        if (fj || (!fi && diff > 0)) {
                            h[i] += diff > 0 ? -1 : 1;
                        } else {
                            h[j] += diff > 0 ? 1 : -1;
                        }
                        changed = true;
                    }
                }
            }
            if (!changed) return;
        }
    }

    /** Mean over a (2r+1)^2 square, clamped at the edges (summed-area table). */
    static double[] boxBlur(int w, int d, int[] v, int r) {
        long[] sat = new long[(w + 1) * (d + 1)];
        for (int z = 0; z < d; z++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += v[x + z * w];
                sat[(x + 1) + (z + 1) * (w + 1)] = sat[(x + 1) + z * (w + 1)] + row;
            }
        }
        double[] out = new double[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r);
                int z0 = Math.max(0, z - r);
                int x1 = Math.min(w, x + r + 1);
                int z1 = Math.min(d, z + r + 1);
                long s = sat[x1 + z1 * (w + 1)] - sat[x0 + z1 * (w + 1)] - sat[x1 + z0 * (w + 1)] + sat[x0 + z0 * (w + 1)];
                out[x + z * w] = s / (double) ((x1 - x0) * (z1 - z0));
            }
        }
        return out;
    }

    /** The largest height difference between neighbouring columns (for checks). */
    static int maxStep(int w, int d, int[] h) {
        int m = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                if (x + 1 < w) m = Math.max(m, Math.abs(h[x + z * w] - h[x + 1 + z * w]));
                if (z + 1 < d) m = Math.max(m, Math.abs(h[x + z * w] - h[x + (z + 1) * w]));
            }
        }
        return m;
    }
}
