package com.kestrel.openmap;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/** {@link Terrain} over the server world: what a player sees of the blocks. */
final class WorldTerrain implements Terrain {
    private final World world;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    WorldTerrain(World world) {
        this.world = world;
    }

    private Block block(int x, int y, int z) {
        if (y < 0 || y > 255) return Blocks.air;
        pos.set(x, y, z);
        if (!world.isBlockLoaded(pos) || !world.getWorldBorder().contains(pos)) return Blocks.bedrock; // treat as a wall
        return world.getBlockState(pos).getBlock();
    }

    @Override
    public boolean solid(int x, int y, int z) {
        Block b = block(x, y, z);
        return b.getMaterial().blocksMovement() && b.getMaterial() != Material.cactus;
    }

    @Override
    public boolean open(int x, int y, int z) {
        Material m = block(x, y, z).getMaterial();
        return !m.blocksMovement() && !m.isLiquid();
    }

    @Override
    public boolean hazard(int x, int y, int z) {
        Block b = block(x, y, z);
        Material m = b.getMaterial();
        return m == Material.lava || m == Material.fire || m == Material.cactus || b == Blocks.web;
    }
}
