package com.kestrel.openmap;

import com.kestrel.KestrelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.File;

/**
 * Opens the Open Map world: loads it when it exists (as the player left it),
 * generates it once otherwise. A map reset requested by the owner is carried
 * out here, before the world opens: the old world folder is renamed aside as
 * a backup (never deleted) and a fresh one is generated. No other world is
 * read or written.
 */
final class OpenMapWorlds {
    private OpenMapWorlds() {
    }

    static File resetFlag(File dataDir) {
        return new File(OpenMapLayout.dir(dataDir), "reset-requested");
    }

    static void open(Minecraft mc, GuiScreen parent) {
        File dataDir = OpenMapServer.get().dataDir();
        File dir = new File(new File(mc.mcDataDir, "saves"), OpenMapWorldSetup.WORLD_FOLDER);
        File flag = resetFlag(dataDir);
        if (flag.isFile()) {
            if (dir.exists()) {
                File aside = new File(dir.getParentFile(), OpenMapWorldSetup.WORLD_FOLDER + "-before-reset-" + System.currentTimeMillis());
                if (!dir.renameTo(aside)) {
                    KestrelMod.LOG.error("[Kestrel] can't move the Open Map world aside for the reset; opening it unchanged");
                } else {
                    KestrelMod.LOG.info("[Kestrel] Open Map reset: kept the old map as {}", aside.getName());
                }
            }
            if (!flag.delete()) KestrelMod.LOG.warn("[Kestrel] could not remove {}", flag);
        }
        if (dir.isDirectory()) {
            WorldInfo info = mc.getSaveLoader().getWorldInfo(OpenMapWorldSetup.WORLD_FOLDER);
            if (info != null) {
                KestrelMod.LOG.info("[Kestrel] opening the Open Map PvP world");
                FMLClientHandler.instance().tryLoadExistingWorld(new net.minecraft.client.gui.GuiSelectWorld(parent),
                        OpenMapWorldSetup.WORLD_FOLDER, info.getWorldName());
                return;
            }
            File aside = new File(dir.getParentFile(), OpenMapWorldSetup.WORLD_FOLDER + "-unreadable-" + System.currentTimeMillis());
            if (!dir.renameTo(aside)) {
                KestrelMod.LOG.error("[Kestrel] the Open Map world can't be read or moved aside: {}", dir);
                return;
            }
            KestrelMod.LOG.warn("[Kestrel] the Open Map world could not be read; kept it as {}", aside.getName());
        }
        KestrelMod.LOG.info("[Kestrel] generating the Open Map PvP world (once)");
        WorldSettings settings = new WorldSettings(OpenMapWorldSetup.SEED, WorldSettings.GameType.ADVENTURE, false, false,
                WorldType.CUSTOMIZED);
        settings.setWorldName(OpenMapWorldSetup.GENERATOR); // customized-world options
        mc.launchIntegratedServer(OpenMapWorldSetup.WORLD_FOLDER, OpenMapWorldSetup.WORLD_NAME, settings);
    }
}
