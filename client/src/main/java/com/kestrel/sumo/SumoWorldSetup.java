package com.kestrel.sumo;

import net.minecraft.block.BlockColored;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;

/**
 * Turns the Sumo world into a practice arena each time it loads: game rules,
 * fixed noon, clear weather, peaceful (no mobs, no hunger) and the platform
 * itself, rebuilt every time so a missing or damaged arena repairs itself.
 * Only ever called for the dedicated Sumo world.
 */
final class SumoWorldSetup {
    private SumoWorldSetup() {
    }

    /** The arena block: light gray hardened clay, a plain neutral block. */
    static IBlockState platformBlock() {
        return Blocks.stained_hardened_clay.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.SILVER);
    }

    static void apply(WorldServer world) {
        GameRules rules = world.getGameRules();
        rules.setOrCreateGameRule("doMobSpawning", "false");
        rules.setOrCreateGameRule("doDaylightCycle", "false");
        rules.setOrCreateGameRule("doFireTick", "false");
        rules.setOrCreateGameRule("mobGriefing", "false");
        rules.setOrCreateGameRule("keepInventory", "true");
        rules.setOrCreateGameRule("doMobLoot", "false");
        rules.setOrCreateGameRule("doTileDrops", "false");
        rules.setOrCreateGameRule("doEntityDrops", "false");
        rules.setOrCreateGameRule("naturalRegeneration", "true");
        rules.setOrCreateGameRule("showDeathMessages", "false");
        rules.setOrCreateGameRule("randomTickSpeed", "0");

        WorldInfo info = world.getWorldInfo();
        info.setDifficulty(EnumDifficulty.PEACEFUL);
        info.setDifficultyLocked(true);
        net.minecraft.server.MinecraftServer.getServer().setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL);
        world.setWorldTime(6000L);
        info.setSpawn(new BlockPos(0, SumoArena.SURFACE_Y, 0));
        clearWeather(info);
        buildArena(world);
    }

    /** Called every tick while a session runs: weather can't start. */
    static void keepClear(WorldServer world) {
        WorldInfo info = world.getWorldInfo();
        if (info.isRaining() || info.isThundering()) clearWeather(info);
    }

    private static void clearWeather(WorldInfo info) {
        info.setCleanWeatherTime(1_000_000);
        info.setRainTime(0);
        info.setThunderTime(0);
        info.setRaining(false);
        info.setThundering(false);
    }

    /** The platform, with clear air above it. Idempotent. */
    static void buildArena(WorldServer world) {
        IBlockState floor = platformBlock();
        IBlockState air = Blocks.air.getDefaultState();
        for (int x = SumoArena.MIN; x <= SumoArena.MAX; x++) {
            for (int z = SumoArena.MIN; z <= SumoArena.MAX; z++) {
                BlockPos pos = new BlockPos(x, SumoArena.FLOOR_Y, z);
                if (world.getBlockState(pos) != floor) world.setBlockState(pos, floor, 2);
                for (int y = 1; y <= SumoArena.HEADROOM; y++) {
                    BlockPos up = pos.up(y);
                    if (world.getBlockState(up).getBlock() != Blocks.air) world.setBlockState(up, air, 2);
                }
            }
        }
    }
}
