package com.kestrel.sumo;

/**
 * An arena of any shape, as a grid of standable block columns (scanned from
 * the world). For every standable column it remembers the nearest column that
 * is not standable, which gives the distance to the edge and the direction of
 * "off" for any point. Pure, so it is unit-tested.
 */
public final class GridArena implements ArenaShape {
    /** How far to look for an edge (blocks); further counts as this far. */
    static final int SEARCH = 12;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final boolean[] walk;
    /** Nearest non-standable column per cell (absolute block coords), or MIN_VALUE if none within SEARCH. */
    private final int[] edgeX;
    private final int[] edgeZ;
    private final double safeX;
    private final double safeZ;
    private final int walkableCount;

    /**
     * @param walkable walkable[x - minX][z - minZ]: whether a player can stand on that block column
     */
    public GridArena(int minX, int minZ, boolean[][] walkable) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = walkable.length;
        this.sizeZ = sizeX == 0 ? 0 : walkable[0].length;
        this.walk = new boolean[sizeX * sizeZ];
        int count = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                walk[x * sizeZ + z] = walkable[x][z];
                if (walkable[x][z]) count++;
            }
        }
        this.walkableCount = count;
        this.edgeX = new int[sizeX * sizeZ];
        this.edgeZ = new int[sizeX * sizeZ];
        double bestD = -1;
        double bx = minX + sizeX / 2.0;
        double bz = minZ + sizeZ / 2.0;
        double sumX = 0;
        double sumZ = 0;
        int sumN = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int i = x * sizeZ + z;
                edgeX[i] = Integer.MIN_VALUE;
                if (!walk[i]) continue;
                int best = Integer.MAX_VALUE;
                for (int dx = -SEARCH; dx <= SEARCH; dx++) {
                    for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                        int d2 = dx * dx + dz * dz;
                        if (d2 >= best || d2 > SEARCH * SEARCH) continue;
                        if (!walkableAt(minX + x + dx, minZ + z + dz)) {
                            best = d2;
                            edgeX[i] = minX + x + dx;
                            edgeZ[i] = minZ + z + dz;
                        }
                    }
                }
                double d = edgeX[i] == Integer.MIN_VALUE ? SEARCH : Math.sqrt(best);
                if (d > bestD + 1e-9) {
                    bestD = d;
                    sumX = 0;
                    sumZ = 0;
                    sumN = 0;
                }
                if (Math.abs(d - bestD) < 1e-9) {
                    sumX += minX + x + 0.5;
                    sumZ += minZ + z + 0.5;
                    sumN++;
                }
            }
        }
        if (sumN > 0) {
            bx = sumX / sumN;
            bz = sumZ / sumN;
        }
        this.safeX = bx;
        this.safeZ = bz;
    }

    public int walkableCount() {
        return walkableCount;
    }

    public boolean walkableAt(int bx, int bz) {
        int x = bx - minX;
        int z = bz - minZ;
        if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ) return false;
        return walk[x * sizeZ + z];
    }

    private int cell(double x, double z) {
        int cx = (int) Math.floor(x) - minX;
        int cz = (int) Math.floor(z) - minZ;
        if (cx < 0 || cz < 0 || cx >= sizeX || cz >= sizeZ) return -1;
        return cx * sizeZ + cz;
    }

    @Override
    public double edgeDistance(double x, double z) {
        int i = cell(x, z);
        if (i < 0 || !walk[i]) return -0.5;
        if (edgeX[i] == Integer.MIN_VALUE) return SEARCH;
        return nearest(x, z, i)[0];
    }

    @Override
    public double[] nearestEdgeNormal(double x, double z) {
        int i = cell(x, z);
        if (i < 0 || !walk[i] || edgeX[i] == Integer.MIN_VALUE) {
            double dx = x - safeX;
            double dz = z - safeZ;
            double l = Math.hypot(dx, dz);
            return l < 1e-6 ? new double[]{1, 0} : new double[]{dx / l, dz / l};
        }
        double[] n = nearest(x, z, i);
        double dx = n[1] - x;
        double dz = n[2] - z;
        double l = Math.hypot(dx, dz);
        if (l < 1e-6) {
            dx = edgeX[i] + 0.5 - x;
            dz = edgeZ[i] + 0.5 - z;
            l = Math.hypot(dx, dz);
        }
        return l < 1e-6 ? new double[]{1, 0} : new double[]{dx / l, dz / l};
    }

    /**
     * Exact nearest point of any non-standable column to (x, z): {distance, px, pz}.
     * The cell's precomputed nearest edge bounds the search.
     */
    private double[] nearest(double x, double z, int i) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        double bound = Math.hypot(edgeX[i] + 0.5 - (cx + 0.5), edgeZ[i] + 0.5 - (cz + 0.5));
        int r = Math.min(SEARCH, (int) Math.ceil(bound) + 1);
        double best = Double.MAX_VALUE;
        double px = x;
        double pz = z;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = cx + dx;
                int bz = cz + dz;
                if (walkableAt(bx, bz)) continue;
                double nx = clamp(x, bx, bx + 1);
                double nz = clamp(z, bz, bz + 1);
                double d = Math.hypot(x - nx, z - nz);
                if (d < best) {
                    best = d;
                    px = nx;
                    pz = nz;
                }
            }
        }
        return new double[]{best, px, pz};
    }

    @Override
    public double safeX() {
        return safeX;
    }

    @Override
    public double safeZ() {
        return safeZ;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
