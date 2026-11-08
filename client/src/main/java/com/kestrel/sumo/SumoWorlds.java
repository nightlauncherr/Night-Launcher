package com.kestrel.sumo;

import com.kestrel.KestrelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.File;

/**
 * Opens the dedicated Sumo practice world: the official map, installed on
 * first use (see {@link OfficialMap}) and loaded as the player left it after
 * that. It is never generated or rebuilt here. No other world is read or written.
 */
final class SumoWorlds {
    private SumoWorlds() {
    }

    static void open(Minecraft mc, GuiSelectWorld screen) {
        File saves = new File(mc.mcDataDir, "saves");
        File dataDir = KestrelMod.client() != null ? KestrelMod.client().dataDir() : new File(mc.mcDataDir, "kestrel");
        // First time (or a pre-official Sumo world): put the official map in place.
        // Afterwards the player's own copy is just loaded.
        if (!OfficialMap.ensureInstalled(saves, dataDir)) return;
        ISaveFormat saves2 = mc.getSaveLoader();
        WorldInfo info = saves2.getWorldInfo(SumoArena.WORLD_FOLDER);
        if (info == null) {
            KestrelMod.LOG.error("[Kestrel] the Sumo world can't be read");
            return;
        }
        KestrelMod.LOG.info("[Kestrel] opening the Sumo practice world");
        FMLClientHandler.instance().tryLoadExistingWorld(screen, SumoArena.WORLD_FOLDER, info.getWorldName());
    }
}
