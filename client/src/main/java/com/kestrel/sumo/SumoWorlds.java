package com.kestrel.sumo;

import com.kestrel.KestrelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.File;

/**
 * Opens the dedicated Sumo practice world: loads it when it exists, creates it
 * otherwise. A save that can't be read is renamed aside (never deleted) and a
 * fresh one is made. No other world is read or written.
 */
final class SumoWorlds {
    private SumoWorlds() {
    }

    static void open(Minecraft mc, GuiSelectWorld screen) {
        ISaveFormat saves = mc.getSaveLoader();
        File dir = new File(new File(mc.mcDataDir, "saves"), SumoArena.WORLD_FOLDER);
        if (dir.isDirectory()) {
            WorldInfo info = saves.getWorldInfo(SumoArena.WORLD_FOLDER);
            if (info != null) {
                KestrelMod.LOG.info("[Kestrel] opening the Sumo practice world");
                FMLClientHandler.instance().tryLoadExistingWorld(screen, SumoArena.WORLD_FOLDER, info.getWorldName());
                return;
            }
            File aside = new File(dir.getParentFile(), SumoArena.WORLD_FOLDER + "-unreadable-" + System.currentTimeMillis());
            if (!dir.renameTo(aside)) {
                KestrelMod.LOG.error("[Kestrel] the Sumo world can't be read and can't be moved aside: {}", dir);
                return;
            }
            KestrelMod.LOG.warn("[Kestrel] the Sumo world could not be read; kept it as {} and made a new one", aside.getName());
        }
        KestrelMod.LOG.info("[Kestrel] creating the Sumo practice world");
        WorldSettings settings = new WorldSettings(0L, WorldSettings.GameType.ADVENTURE, false, false, WorldType.FLAT);
        settings.setWorldName(SumoArena.GENERATOR); // superflat generator options: a void
        mc.launchIntegratedServer(SumoArena.WORLD_FOLDER, SumoArena.WORLD_NAME, settings);
    }
}
