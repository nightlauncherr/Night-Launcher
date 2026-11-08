package com.kestrel.sumo;

import net.minecraft.block.BlockColored;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;

/**
 * Practice rules for the Sumo world (applied on every load) and the default
 * arena and lobby (built only when the world is first made, or when the player
 * asks for it; edits are never overwritten on load). Only ever called for the
 * dedicated Sumo world.
 */
final class SumoWorldSetup {
    private SumoWorldSetup() {
    }

    static IBlockState material(String id) {
        if ("stone".equals(id)) return Blocks.stone.getDefaultState();
        if ("white_wool".equals(id)) return Blocks.wool.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.WHITE);
        if ("quartz".equals(id)) return Blocks.quartz_block.getDefaultState();
        if ("sandstone".equals(id)) return Blocks.sandstone.getDefaultState();
        if ("snow".equals(id)) return Blocks.snow.getDefaultState();
        if ("black_clay".equals(id)) {
            return Blocks.stained_hardened_clay.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.BLACK);
        }
        return Blocks.stained_hardened_clay.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.SILVER);
    }

    static IBlockState lobbyBlock() {
        return Blocks.stained_hardened_clay.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.PURPLE);
    }

    /** Game rules, time, weather and difficulty. Never touches blocks. */
    static void applyRules(WorldServer world) {
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
        MinecraftServer.getServer().setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL);
        world.setWorldTime(6000L);
        clearWeather(info);
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

    /**
     * Build the default layout: clear the Sumo area, then a square platform of
     * the layout's size and block, and the lobby platform. Callers keep a backup
     * of what was there first when it may hold the player's work.
     */
    static void buildDefault(WorldServer world, ArenaLayout layout) {
        IBlockState air = Blocks.air.getDefaultState();
        for (int x = layout.regionMinX; x <= layout.regionMaxX; x++) {
            for (int z = layout.regionMinZ; z <= layout.regionMaxZ; z++) {
                for (int y = layout.regionMinY; y <= layout.regionMaxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).getBlock().getMaterial() != Material.air) world.setBlockState(pos, air, 2);
                }
            }
        }
        IBlockState floor = material(layout.platformMaterial);
        int half = layout.platformSize / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                world.setBlockState(new BlockPos(x, SumoArena.FLOOR_Y, z), floor, 2);
            }
        }
        IBlockState lobby = lobbyBlock();
        int lx = (int) Math.floor(layout.lobbyCenterX());
        int lz = (int) Math.floor(layout.lobbyCenterZ());
        for (int x = lx - ArenaLayout.LOBBY_HALF; x <= lx + ArenaLayout.LOBBY_HALF; x++) {
            for (int z = lz - ArenaLayout.LOBBY_HALF; z <= lz + ArenaLayout.LOBBY_HALF; z++) {
                world.setBlockState(new BlockPos(x, SumoArena.FLOOR_Y, z), lobby, 2);
            }
        }
        world.getWorldInfo().setSpawn(new BlockPos(layout.lobbySpawn.x, layout.lobbySpawn.y, layout.lobbySpawn.z));
    }
}
