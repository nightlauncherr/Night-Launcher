package com.kestrel.openmap;

/**
 * What the opponent can know about the blocks around it (the same a player
 * sees). Implemented over the server world; plain in tests.
 */
public interface Terrain {
    /** A block you can stand on / that blocks movement. */
    boolean solid(int x, int y, int z);

    /** Free to walk through: no collision and not a liquid. */
    boolean open(int x, int y, int z);

    /** Never step here: lava, fire, cactus, deep water. */
    boolean hazard(int x, int y, int z);

    /** A position a player could stand at: feet and head free, solid below, nothing dangerous. */
    default boolean standable(int x, int y, int z) {
        return open(x, y, z) && open(x, y + 1, z) && solid(x, y - 1, z)
                && !hazard(x, y, z) && !hazard(x, y + 1, z) && !hazard(x, y - 1, z);
    }
}
