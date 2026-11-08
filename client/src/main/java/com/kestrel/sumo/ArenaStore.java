package com.kestrel.sumo;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Block snapshots of the Sumo area (arena and lobby), for {@code /sumo save}
 * and {@code /sumo reset}. A snapshot holds every block state in the layout's
 * region plus the layout (spawns) it was saved with. Writing one always keeps
 * the previous file as {@code .prev}. Blocks only: container contents and sign
 * text are not part of it.
 */
final class ArenaStore {
    private ArenaStore() {
    }

    /** Snapshot the region of {@code layout} into {@code file}. Returns the number of non-air blocks. */
    static int save(WorldServer world, ArenaLayout layout, File file) throws IOException {
        int sx = layout.regionMaxX - layout.regionMinX + 1;
        int sy = layout.regionMaxY - layout.regionMinY + 1;
        int sz = layout.regionMaxZ - layout.regionMinZ + 1;
        int[] states = new int[sx * sy * sz];
        int solid = 0;
        int i = 0;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    IBlockState st = world.getBlockState(new BlockPos(layout.regionMinX + x, layout.regionMinY + y, layout.regionMinZ + z));
                    states[i++] = Block.getStateId(st);
                    if (st.getBlock().getMaterial() != Material.air) solid++;
                }
            }
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", 1);
        root.setInteger("minX", layout.regionMinX);
        root.setInteger("minY", layout.regionMinY);
        root.setInteger("minZ", layout.regionMinZ);
        root.setInteger("sizeX", sx);
        root.setInteger("sizeY", sy);
        root.setInteger("sizeZ", sz);
        root.setIntArray("blocks", states);
        root.setString("layout", layoutJson(layout));
        root.setLong("saved", System.currentTimeMillis());
        write(root, file);
        return solid;
    }

    /** Restore blocks from {@code file}; returns the layout saved with it, or null when there is no usable snapshot. */
    static ArenaLayout restore(WorldServer world, File file) throws IOException {
        if (!file.isFile()) return null;
        NBTTagCompound root;
        try (InputStream in = new FileInputStream(file)) {
            root = CompressedStreamTools.readCompressed(in);
        }
        int minX = root.getInteger("minX");
        int minY = root.getInteger("minY");
        int minZ = root.getInteger("minZ");
        int sx = root.getInteger("sizeX");
        int sy = root.getInteger("sizeY");
        int sz = root.getInteger("sizeZ");
        int[] states = root.getIntArray("blocks");
        if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz != states.length || minY < 0 || minY + sy > 256) {
            throw new IOException("the saved arena is damaged");
        }
        int i = 0;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    IBlockState want = Block.getStateById(states[i++]);
                    BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                    if (world.getBlockState(pos) != want) world.setBlockState(pos, want, 2);
                }
            }
        }
        ArenaLayout saved = parseLayout(root.getString("layout"));
        return saved;
    }

    /** Keep a copy of the current region before something replaces it (default or rebuilt platform). */
    static File backupCurrent(WorldServer world, ArenaLayout layout, File dir, String reason) throws IOException {
        File f = new File(dir, "arena-blocks." + reason + "-" + System.currentTimeMillis() + ".dat");
        save(world, layout, f);
        return f;
    }

    private static void write(NBTTagCompound root, File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        ArenaLayout.backup(file);
        File tmp = new File(dir, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            CompressedStreamTools.writeCompressed(root, out);
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String layoutJson(ArenaLayout layout) {
        return new com.google.gson.Gson().toJson(layout);
    }

    private static ArenaLayout parseLayout(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            ArenaLayout l = new com.google.gson.Gson().fromJson(json, ArenaLayout.class);
            if (l != null) l.normalize();
            return l;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
