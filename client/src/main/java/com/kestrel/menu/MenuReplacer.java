package com.kestrel.menu;

import com.kestrel.KestrelClient;
import com.kestrel.skin.SkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Shows {@link KestrelMainMenu} wherever the game would open its own main menu
 * (start-up, leaving a world, disconnecting, closing a menu with no world).
 * {@code Minecraft.displayGuiScreen} posts {@link GuiOpenEvent} for every
 * screen, so swapping it here covers every path without touching vanilla code.
 *
 * <p>While you move around the menus (vanilla screens such as Options or Mods
 * close with "open the main menu"), the same menu instance comes back, so the
 * character stays standing. After you have been in a world, or at start-up,
 * a fresh menu plays the entrance again.
 */
public final class MenuReplacer {
    private final KestrelClient client;
    private final SkinManager skins;
    /** The menu to come back to; dropped once a world is loaded. */
    private KestrelMainMenu current;

    public MenuReplacer(KestrelClient client, SkinManager skins) {
        this.client = client;
        this.skins = skins;
    }

    @SubscribeEvent
    public void onOpen(GuiOpenEvent event) {
        if (event.gui instanceof GuiMainMenu) {
            if (current == null) current = new KestrelMainMenu(client, skins);
            event.gui = current;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && current != null && Minecraft.getMinecraft().theWorld != null) {
            current = null; // in a world: the next menu is a fresh one with the entrance
        }
    }
}
