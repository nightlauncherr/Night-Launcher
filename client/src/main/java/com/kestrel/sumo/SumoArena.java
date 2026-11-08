package com.kestrel.sumo;

/**
 * Geometry of the Sumo practice arena: one square platform floating in a void
 * world. Pure (no Minecraft types) so positions, edges and fall detection are
 * unit-tested; {@link SumoWorldSetup} turns it into blocks.
 *
 * <p>Coordinates are Minecraft world coordinates. The platform covers block
 * columns {@code MIN..MAX} on X and Z at {@code FLOOR_Y}; players stand on its
 * top face at {@code SURFACE_Y}. Yaw follows Minecraft (0 = +Z, 90 = -X,
 * -90 = +X).
 */
public final class SumoArena {
    /** Folder name of the dedicated practice world under {@code saves/}. */
    public static final String WORLD_FOLDER = "night-sumo-practice";
    /** Display name of that world in the Singleplayer list. */
    public static final String WORLD_NAME = "Night Sumo Practice";
    /** Superflat preset with a single air layer: an empty void world. */
    public static final String GENERATOR = "3;minecraft:air;1;";

    /** First and last block column of the platform on both axes (15 x 15). */
    public static final int MIN = -7;
    public static final int MAX = 7;
    /** Y of the platform blocks; players stand one block higher. */
    public static final int FLOOR_Y = 99;
    public static final int SURFACE_Y = FLOOR_Y + 1;
    /** Air kept clear above the platform (blocks). */
    public static final int HEADROOM = 6;
    /** Feet below this height count as fallen off (well past any recovery). */
    public static final double LOSE_Y = SURFACE_Y - 3.0;

    /** Centre of the platform's top face. */
    public static final double CENTER_X = (MIN + MAX + 1) / 2.0;
    public static final double CENTER_Z = (MIN + MAX + 1) / 2.0;
    /** Distance of each start position from the centre (they are twice this apart). */
    public static final double SPAWN_OFFSET = 3.5;

    private SumoArena() {
    }

    /** Edge coordinates of the platform's top face (the outside of the outer blocks). */
    public static double minEdge() {
        return MIN;
    }

    public static double maxEdge() {
        return MAX + 1;
    }

    /** Player start: west side, facing east (+X). Returns {x, y, z, yaw}. */
    public static double[] playerSpawn() {
        return new double[]{CENTER_X - SPAWN_OFFSET, SURFACE_Y, CENTER_Z, -90.0};
    }

    /** Opponent start: east side, facing west (-X). Returns {x, y, z, yaw}. */
    public static double[] botSpawn() {
        return new double[]{CENTER_X + SPAWN_OFFSET, SURFACE_Y, CENTER_Z, 90.0};
    }

    /** Horizontal distance from (x, z) to the nearest platform edge; negative when outside. */
    public static double edgeDistance(double x, double z) {
        double dx = Math.min(x - minEdge(), maxEdge() - x);
        double dz = Math.min(z - minEdge(), maxEdge() - z);
        return Math.min(dx, dz);
    }

    /**
     * Outward unit normal of the edge nearest to (x, z), as {nx, nz}: the
     * direction in which that position is closest to falling off.
     */
    public static double[] nearestEdgeNormal(double x, double z) {
        double west = x - minEdge();
        double east = maxEdge() - x;
        double north = z - minEdge();
        double south = maxEdge() - z;
        double best = Math.min(Math.min(west, east), Math.min(north, south));
        if (best == east) return new double[]{1, 0};
        if (best == west) return new double[]{-1, 0};
        if (best == south) return new double[]{0, 1};
        return new double[]{0, -1};
    }

    /** Whether (x, z) is above the platform. */
    public static boolean overPlatform(double x, double z) {
        return edgeDistance(x, z) >= 0;
    }

    /** Whether feet at height y have fallen off the arena. */
    public static boolean fell(double y) {
        return y < LOSE_Y;
    }
}
