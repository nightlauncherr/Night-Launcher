package com.kestrel.openmap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import net.minecraft.world.gen.feature.WorldGenTrees;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Terrain version 2 of the Open Map: the existing world made flatter in place
 * (see {@link TerrainPlan}), not replaced. Runs once, on the server before the
 * player joins, after the world folder was copied aside as a backup.
 *
 * <ul>
 *   <li>Every column's ground goes to its planned height: grass on top, three
 *       dirt below, stone filling any gap underneath.</li>
 *   <li>Columns holding anything a player built (any block that isn't natural
 *       terrain or plants), and the lobby, are not touched; the ground around
 *       them is shaped to meet them.</li>
 *   <li>Trees are replanted where they stood, fewer of them, mostly toward the
 *       edge of the map; the middle (where fights happen) stays open.</li>
 *   <li>Tall grass and flowers are left out of the combat area; a little comes
 *       back toward the edges so the map still looks natural.</li>
 * </ul>
 */
final class OpenMapTerrain {
    static final int VERSION = 2;
    /** Radius around the map centre that is kept clear for fighting. */
    private static final int COMBAT_RADIUS = 85;
    private static final int CLEAR_TOP = 160;

    private OpenMapTerrain() {
    }

    /** Natural blocks (terrain, water, plants, trees). Anything else marks a built column. */
    private static Set<Block> natural() {
        Set<Block> s = new HashSet<Block>();
        Block[] list = {Blocks.air, Blocks.grass, Blocks.dirt, Blocks.stone, Blocks.gravel, Blocks.sand, Blocks.sandstone,
                Blocks.clay, Blocks.water, Blocks.flowing_water, Blocks.lava, Blocks.flowing_lava, Blocks.bedrock,
                Blocks.coal_ore, Blocks.iron_ore, Blocks.gold_ore, Blocks.diamond_ore, Blocks.redstone_ore,
                Blocks.lit_redstone_ore, Blocks.lapis_ore, Blocks.emerald_ore, Blocks.log, Blocks.log2, Blocks.leaves,
                Blocks.leaves2, Blocks.tallgrass, Blocks.deadbush, Blocks.yellow_flower, Blocks.red_flower,
                Blocks.double_plant, Blocks.snow_layer, Blocks.ice, Blocks.reeds, Blocks.vine, Blocks.brown_mushroom,
                Blocks.red_mushroom, Blocks.pumpkin, Blocks.waterlily, Blocks.cactus, Blocks.mossy_cobblestone};
        for (Block b : list) s.add(b);
        return s;
    }

    private static boolean ground(Block b) {
        return b == Blocks.grass || b == Blocks.dirt || b == Blocks.stone || b == Blocks.gravel || b == Blocks.sand
                || b == Blocks.sandstone || b == Blocks.clay || b == Blocks.water || b == Blocks.flowing_water
                || b == Blocks.bedrock || b == Blocks.coal_ore || b == Blocks.iron_ore || b == Blocks.ice;
    }

    /**
     * Reshape the play area. Returns the number of columns changed. Start
     * positions are moved onto the new ground (same x/z); the lobby isn't.
     */
    static int apply(WorldServer world, OpenMapLayout layout, Logger log) {
        long started = System.currentTimeMillis();
        int half = layout.size / 2;
        int x0 = (int) Math.floor(layout.centerX) - half;
        int z0 = (int) Math.floor(layout.centerZ) - half;
        int w = layout.size;
        int d = layout.size;
        Set<Block> natural = natural();
        int[] height = new int[w * d];
        boolean[] fixed = new boolean[w * d];
        // For built columns: the lowest and highest built (non-natural) block.
        int[] builtLow = new int[w * d];
        int[] builtHigh = new int[w * d];
        List<BlockPos> trunks = new ArrayList<BlockPos>();
        Map<Chunk, Boolean> touched = new IdentityHashMap<Chunk, Boolean>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int lobbyX = (int) Math.floor(layout.lobbySpawn.x);
        int lobbyZ = (int) Math.floor(layout.lobbySpawn.z);
        int lobbyFloor = (int) Math.floor(layout.lobbySpawn.y) - 1;
        // 1. Read the current ground, find built columns and tree trunks.
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int wx = x0 + x;
                int wz = z0 + z;
                int i = x + z * w;
                Chunk c = world.getChunkFromBlockCoords(new BlockPos(wx, 0, wz));
                int top = -1;
                builtLow[i] = Integer.MAX_VALUE;
                builtHigh[i] = -1;
                for (int y = CLEAR_TOP; y >= 1; y--) {
                    p.set(wx, y, wz);
                    Block b = c.getBlock(p);
                    if (b == Blocks.air) continue;
                    if (!natural.contains(b)) {
                        fixed[i] = true;
                        builtLow[i] = Math.min(builtLow[i], y);
                        builtHigh[i] = Math.max(builtHigh[i], y);
                    }
                    if ((b == Blocks.log || b == Blocks.log2)) {
                        p.set(wx, y - 1, wz);
                        Block below = c.getBlock(p);
                        if (below == Blocks.grass || below == Blocks.dirt) trunks.add(new BlockPos(wx, y, wz));
                    }
                    if (top < 0 && ground(b)) top = y;
                }
                height[i] = top < 0 ? lobbyFloor - 1 : top;
                // A built column stands on its lowest built block (the lobby floor, a player's build).
                if (fixed[i]) height[i] = builtLow[i];
            }
        }
        // 2. Plan the new ground.
        int base = lobbyFloor - 1;
        int[] plan = TerrainPlan.plan(w, d, height, fixed, base, lobbyX - x0, lobbyZ - z0, 9);
        // 3. Rewrite each free column.
        IBlockState air = Blocks.air.getDefaultState();
        IBlockState grass = Blocks.grass.getDefaultState();
        IBlockState dirt = Blocks.dirt.getDefaultState();
        IBlockState stone = Blocks.stone.getDefaultState();
        int changed = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int i = x + z * w;
                int wx = x0 + x;
                int wz = z0 + z;
                if (fixed[i]) {
                    // Keep every built block; only clear natural terrain overhanging the build.
                    Chunk c = world.getChunkFromBlockCoords(new BlockPos(wx, 0, wz));
                    boolean any = false;
                    for (int y = CLEAR_TOP; y > builtHigh[i] + 1; y--) {
                        p.set(wx, y, wz);
                        Block b = c.getBlock(p);
                        if (b != Blocks.air && natural.contains(b)) {
                            c.setBlockState(new BlockPos(wx, y, wz), air);
                            any = true;
                        }
                    }
                    if (any) {
                        changed++;
                        touched.put(c, Boolean.TRUE);
                    }
                    continue;
                }
                int t = plan[i];
                Chunk c = world.getChunkFromBlockCoords(new BlockPos(wx, 0, wz));
                boolean any = false;
                for (int y = CLEAR_TOP; y > t; y--) {
                    p.set(wx, y, wz);
                    if (c.getBlock(p) != Blocks.air) {
                        c.setBlockState(new BlockPos(wx, y, wz), air);
                        any = true;
                    }
                }
                any |= set(c, wx, t, wz, grass);
                for (int y = t - 1; y >= t - 3; y--) any |= set(c, wx, y, wz, dirt);
                for (int y = t - 4; y >= 1; y--) {
                    p.set(wx, y, wz);
                    Block b = c.getBlock(p);
                    if (b == Blocks.stone || b == Blocks.bedrock) break;
                    if (b == Blocks.air || b.getMaterial().isLiquid() || b == Blocks.dirt || b == Blocks.grass
                            || b == Blocks.gravel || b == Blocks.sand) {
                        any |= set(c, wx, y, wz, stone);
                    } else {
                        break;
                    }
                }
                if (any) {
                    changed++;
                    touched.put(c, Boolean.TRUE);
                }
            }
        }
        for (Chunk c : touched.keySet()) {
            c.generateSkylightMap();
            c.setLightPopulated(false); // the game finishes the lighting as it ticks
            c.setChunkModified();
        }
        // 4. Trees back where they stood: few in the middle, more toward the edges.
        Random rng = new Random(OpenMapWorldSetup.SEED + 2);
        WorldGenTrees oak = new WorldGenTrees(true);
        WorldGenBigTree big = new WorldGenBigTree(true);
        List<BlockPos> kept = new ArrayList<BlockPos>();
        int planted = 0;
        for (BlockPos t : trunks) {
            int lx = t.getX() - x0;
            int lz = t.getZ() - z0;
            if (lx < 2 || lz < 2 || lx >= w - 2 || lz >= d - 2 || fixed[lx + lz * w]) continue;
            double dc = Math.hypot(t.getX() + 0.5 - layout.centerX, t.getZ() + 0.5 - layout.centerZ);
            double keep = dc >= COMBAT_RADIUS ? 0.55 : 0.10;
            if (rng.nextDouble() >= keep) continue;
            if (near(t, layout.lobbySpawn, 10) || near(t, layout.playerSpawn, 8) || near(t, layout.aiSpawn, 8)) continue;
            boolean crowded = false;
            for (BlockPos k : kept) {
                if (Math.abs(k.getX() - t.getX()) < 5 && Math.abs(k.getZ() - t.getZ()) < 5) {
                    crowded = true;
                    break;
                }
            }
            if (crowded) continue;
            BlockPos at = new BlockPos(t.getX(), plan[lx + lz * w] + 1, t.getZ());
            boolean ok = rng.nextInt(25) == 0 ? big.generate(world, rng, at) : oak.generate(world, rng, at);
            if (ok) {
                kept.add(t);
                planted++;
            }
        }
        // 5. A little tall grass and a few flowers toward the edges only.
        IBlockState tall = Blocks.tallgrass.getDefaultState().withProperty(BlockTallGrass.TYPE, BlockTallGrass.EnumType.GRASS);
        IBlockState flower = Blocks.yellow_flower.getDefaultState();
        int plants = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int i = x + z * w;
                if (fixed[i]) continue;
                int wx = x0 + x;
                int wz = z0 + z;
                double dc = Math.hypot(wx + 0.5 - layout.centerX, wz + 0.5 - layout.centerZ);
                if (dc < COMBAT_RADIUS) continue;
                double r = rng.nextDouble();
                BlockPos above = new BlockPos(wx, plan[i] + 1, wz);
                if (world.getBlockState(above).getBlock() != Blocks.air
                        || world.getBlockState(above.down()).getBlock() != Blocks.grass) continue;
                if (r < 0.06) {
                    world.setBlockState(above, tall, 2);
                    plants++;
                } else if (r < 0.07) {
                    world.setBlockState(above, flower, 2);
                    plants++;
                }
            }
        }
        // 6. Start positions onto the new ground.
        layout.playerSpawn = reseat(layout.playerSpawn, plan, x0, z0, w, d);
        layout.aiSpawn = reseat(layout.aiSpawn, plan, x0, z0, w, d);
        log.info("[Kestrel] Open Map terrain v2: {} columns reshaped, {} of {} trees replanted, {} plants, {} built columns kept, {} ms",
                changed, planted, trunks.size(), plants, count(fixed), System.currentTimeMillis() - started);
        return changed;
    }

    private static boolean set(Chunk c, int x, int y, int z, IBlockState s) {
        BlockPos pos = new BlockPos(x, y, z);
        if (c.getBlockState(pos) == s) return false;
        c.setBlockState(pos, s);
        return true;
    }

    private static OpenMapLayout.Spot reseat(OpenMapLayout.Spot s, int[] plan, int x0, int z0, int w, int d) {
        int x = (int) Math.floor(s.x) - x0;
        int z = (int) Math.floor(s.z) - z0;
        if (x < 0 || z < 0 || x >= w || z >= d) return s;
        return new OpenMapLayout.Spot(s.x, plan[x + z * w] + 1, s.z, s.yaw);
    }

    private static boolean near(BlockPos p, OpenMapLayout.Spot s, double r) {
        double dx = p.getX() + 0.5 - s.x;
        double dz = p.getZ() + 0.5 - s.z;
        return dx * dx + dz * dz < r * r;
    }

    private static int count(boolean[] b) {
        int n = 0;
        for (boolean v : b) if (v) n++;
        return n;
    }
}
