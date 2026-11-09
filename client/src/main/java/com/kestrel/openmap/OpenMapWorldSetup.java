package com.kestrel.openmap;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldServer;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import net.minecraft.world.gen.feature.WorldGenTrees;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

/**
 * The Open Map world: a vanilla "Customized" world (fixed seed) of hilly grass
 * plains without caves, lakes, ravines or structures, inside a 256 x 256 world
 * border. On first load it gets a small stone-brick lobby, scattered oak trees
 * and the two start positions; after that nothing here touches blocks again.
 */
final class OpenMapWorldSetup {
    static final String WORLD_FOLDER = "night-openmap-pvp";
    static final String WORLD_NAME = "Night Open Map PvP";
    static final long SEED = 8_417_220_391L;
    /** Vanilla customized-world options: plains only, more height variation, no caves/lakes/structures. */
    static final String GENERATOR = "{\"fixedBiome\":1,\"seaLevel\":50,\"biomeScaleOffset\":0.25,\"biomeScaleWeight\":1.5,"
            + "\"useCaves\":false,\"useDungeons\":false,\"useLavaLakes\":false,\"useWaterLakes\":false,\"useRavines\":false,"
            + "\"useMineShafts\":false,\"useVillages\":false,\"useStrongholds\":false,\"useTemples\":false,"
            + "\"useMonuments\":false,\"useLavaOceans\":false}";
    /** Written into the world folder once the lobby and trees are in place. */
    static final String MARKER = "night-openmap.json";
    static final int SIZE = 256;
    private static final int LOBBY_HALF = 4;
    private static final int TREES = 230;

    private OpenMapWorldSetup() {
    }

    /** Game rules, time, weather, difficulty. Never touches blocks. */
    static void applyRules(WorldServer world) {
        GameRules rules = world.getGameRules();
        rules.setOrCreateGameRule("doMobSpawning", "false");
        rules.setOrCreateGameRule("doDaylightCycle", "false");
        rules.setOrCreateGameRule("doFireTick", "false");
        rules.setOrCreateGameRule("mobGriefing", "false");
        rules.setOrCreateGameRule("keepInventory", "true");
        rules.setOrCreateGameRule("naturalRegeneration", "true");
        rules.setOrCreateGameRule("showDeathMessages", "false");
        rules.setOrCreateGameRule("randomTickSpeed", "0"); // no grass spreading or leaf decay to undo
        WorldInfo info = world.getWorldInfo();
        // Easy: hunger and regeneration as normal; no mobs spawn (doMobSpawning off).
        info.setDifficulty(EnumDifficulty.EASY);
        info.setDifficultyLocked(true);
        MinecraftServer.getServer().setDifficultyForAllWorlds(EnumDifficulty.EASY);
        world.setWorldTime(6000L);
        keepClear(world);
    }

    static void keepClear(WorldServer world) {
        WorldInfo info = world.getWorldInfo();
        if (info.isRaining() || info.isThundering()) {
            info.setCleanWeatherTime(1_000_000);
            info.setRainTime(0);
            info.setThunderTime(0);
            info.setRaining(false);
            info.setThundering(false);
        }
    }

    static boolean isSetUp(WorldServer world) {
        return new File(world.getSaveHandler().getWorldDirectory(), MARKER).isFile();
    }

    /** First load only: border, lobby, trees, start positions. */
    static OpenMapLayout setUp(WorldServer world) throws IOException {
        BlockPos spawn = world.getSpawnPoint();
        BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(spawn.getX(), 0, spawn.getZ()));
        int cx = spawn.getX();
        int cz = spawn.getZ();
        int gy = Math.max(top.getY() - 1, 52);
        // World border: 256 x 256 around the lobby.
        WorldBorder border = world.getWorldBorder();
        border.setCenter(cx + 0.5, cz + 0.5 + 20);
        border.setTransition(SIZE);
        buildLobby(world, cx, gy, cz);
        OpenMapLayout l = new OpenMapLayout();
        l.centerX = cx + 0.5;
        l.centerZ = cz + 0.5 + 20;
        l.size = SIZE;
        l.lobbySpawn = new OpenMapLayout.Spot(cx + 0.5, gy + 1, cz + 0.5, 0F);
        l.playerSpawn = surface(world, cx - 12, cz + 28, -90F);
        l.aiSpawn = surface(world, cx + 13, cz + 28, 90F);
        plantTrees(world, l, new Random(SEED));
        world.getWorldInfo().setSpawn(new BlockPos(cx, gy + 1, cz));
        File marker = new File(world.getSaveHandler().getWorldDirectory(), MARKER);
        Files.write(marker.toPath(), ("{\"version\":1,\"seed\":" + SEED + "}").getBytes(StandardCharsets.UTF_8));
        return l;
    }

    /** A standing spot on the ground at (x, z), clearing a tree trunk if one is in the way. */
    static OpenMapLayout.Spot surface(WorldServer world, int x, int z, float yaw) {
        BlockPos p = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z));
        for (int y = p.getY(); y <= p.getY() + 1; y++) {
            BlockPos q = new BlockPos(x, y, z);
            if (world.getBlockState(q).getBlock().getMaterial() != Material.air) world.setBlockToAir(q);
        }
        if (world.getBlockState(p.down()).getBlock().getMaterial().isLiquid()) {
            world.setBlockState(p.down(), Blocks.grass.getDefaultState(), 2);
        }
        return new OpenMapLayout.Spot(x + 0.5, p.getY(), z + 0.5, yaw);
    }

    /** A 9 x 9 stone-brick lobby with a fence around it, open toward the fighting area. */
    private static void buildLobby(WorldServer world, int cx, int gy, int cz) {
        IBlockState floor = Blocks.stonebrick.getDefaultState();
        IBlockState fence = Blocks.oak_fence.getDefaultState();
        for (int x = -LOBBY_HALF; x <= LOBBY_HALF; x++) {
            for (int z = -LOBBY_HALF; z <= LOBBY_HALF; z++) {
                // Solid underneath down to the ground, so it never floats.
                for (int y = gy - 1; y > gy - 8; y--) {
                    BlockPos under = new BlockPos(cx + x, y, cz + z);
                    Material m = world.getBlockState(under).getBlock().getMaterial();
                    if (m.blocksMovement() && m != Material.leaves && m != Material.wood) break;
                    world.setBlockState(under, Blocks.dirt.getDefaultState(), 2);
                }
                world.setBlockState(new BlockPos(cx + x, gy, cz + z), floor, 2);
                for (int y = gy + 1; y <= gy + 5; y++) world.setBlockToAir(new BlockPos(cx + x, y, cz + z));
                boolean edge = Math.abs(x) == LOBBY_HALF || Math.abs(z) == LOBBY_HALF;
                boolean gate = z == LOBBY_HALF && Math.abs(x) <= 1;
                if (edge && !gate) world.setBlockState(new BlockPos(cx + x, gy + 1, cz + z), fence, 2);
            }
        }
    }

    /** Scattered vanilla oak trees (and a few big ones), away from the lobby and the start positions. */
    private static void plantTrees(WorldServer world, OpenMapLayout l, Random rng) {
        WorldGenTrees oak = new WorldGenTrees(true);
        WorldGenBigTree big = new WorldGenBigTree(true);
        int half = SIZE / 2 - 4;
        int planted = 0;
        for (int attempt = 0; attempt < TREES * 4 && planted < TREES; attempt++) {
            int x = (int) Math.floor(l.centerX) + rng.nextInt(half * 2) - half;
            int z = (int) Math.floor(l.centerZ) + rng.nextInt(half * 2) - half;
            if (near(x, z, l.lobbySpawn, 9) || near(x, z, l.playerSpawn, 5) || near(x, z, l.aiSpawn, 5)) continue;
            BlockPos p = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z));
            Block ground = world.getBlockState(p.down()).getBlock();
            if (ground != Blocks.grass && ground != Blocks.dirt) continue;
            boolean ok = rng.nextInt(20) == 0 ? big.generate(world, rng, p) : oak.generate(world, rng, p);
            if (ok) planted++;
        }
    }

    private static boolean near(int x, int z, OpenMapLayout.Spot s, double r) {
        double dx = x + 0.5 - s.x;
        double dz = z + 0.5 - s.z;
        return dx * dx + dz * dz < r * r;
    }
}
