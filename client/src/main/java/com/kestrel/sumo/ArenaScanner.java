package com.kestrel.sumo;

import net.minecraft.block.material.Material;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayDeque;

/**
 * Reads the arena's shape from the world before a match, so the opponent's
 * edge awareness follows whatever the player built. Only the platform the
 * start positions stand on counts (connected columns), so the lobby or other
 * builds nearby are not mistaken for safe ground.
 */
final class ArenaScanner {
    private ArenaScanner() {
    }

    /** Can a player stand on the column at (bx, bz) near height {@code feetY}? */
    static boolean standable(WorldServer world, int bx, int bz, int feetY) {
        for (int y = feetY + 2; y >= feetY - 3; y--) {
            if (y < 1 || y > 254) continue;
            BlockPos pos = new BlockPos(bx, y - 1, bz);
            Material floor = world.getBlockState(pos).getBlock().getMaterial();
            if (floor.blocksMovement()
                    && !world.getBlockState(pos.up()).getBlock().getMaterial().blocksMovement()
                    && !world.getBlockState(pos.up(2)).getBlock().getMaterial().blocksMovement()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a start position has ground under it. */
    static boolean hasFloor(WorldServer world, ArenaLayout.Spot s) {
        return standable(world, floor(s.x), floor(s.z), floor(s.y));
    }

    /**
     * The platform both start positions share, or null when either start has no
     * floor or they are not on the same connected platform.
     */
    static GridArena scan(WorldServer world, ArenaLayout layout) {
        if (!hasFloor(world, layout.playerSpawn) || !hasFloor(world, layout.aiSpawn)) return null;
        int feetY = floor(Math.min(layout.playerSpawn.y, layout.aiSpawn.y));
        int minX = layout.regionMinX;
        int minZ = layout.regionMinZ;
        int sx = layout.regionMaxX - minX + 1;
        int sz = layout.regionMaxZ - minZ + 1;
        boolean[][] stand = new boolean[sx][sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                stand[x][z] = standable(world, minX + x, minZ + z, feetY);
            }
        }
        // Keep the platform connected to the opponent's start (8-connected, like walking).
        boolean[][] keep = new boolean[sx][sz];
        int ax = floor(layout.aiSpawn.x) - minX;
        int az = floor(layout.aiSpawn.z) - minZ;
        if (ax < 0 || az < 0 || ax >= sx || az >= sz || !stand[ax][az]) return null;
        ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
        keep[ax][az] = true;
        queue.add(new int[]{ax, az});
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = c[0] + dx;
                    int nz = c[1] + dz;
                    if (nx < 0 || nz < 0 || nx >= sx || nz >= sz || keep[nx][nz] || !stand[nx][nz]) continue;
                    keep[nx][nz] = true;
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        int px = floor(layout.playerSpawn.x) - minX;
        int pz = floor(layout.playerSpawn.z) - minZ;
        if (px < 0 || pz < 0 || px >= sx || pz >= sz || !keep[px][pz]) return null;
        return new GridArena(minX, minZ, keep);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
