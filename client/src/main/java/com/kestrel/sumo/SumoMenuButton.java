package com.kestrel.sumo;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Adds CREATE SUMO WORLD to the top left of the Singleplayer world list. */
public final class SumoMenuButton {
    static final int BUTTON_ID = 0x5A40;
    static final String LABEL = "CREATE SUMO WORLD";

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiSelectWorld)) return;
        int w = 132;
        event.buttonList.add(new NightButton(BUTTON_ID, 6, 6, w, 20, LABEL));
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.gui instanceof GuiSelectWorld) || event.button.id != BUTTON_ID) return;
        event.setCanceled(true);
        event.button.enabled = false; // one click opens it once
        SumoWorlds.open(Minecraft.getMinecraft(), (GuiSelectWorld) event.gui);
    }

    /** A button in Night's purple style. */
    static final class NightButton extends GuiButton {
        NightButton(int id, int x, int y, int w, int h, String text) {
            super(id, x, y, w, h, text);
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            hovered = mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height;
            int bg = !enabled ? Theme.TRACK : (hovered ? Theme.ACCENT_HOVER : Theme.ACCENT);
            RenderUtil.rect(xPosition, yPosition, xPosition + width, yPosition + height, bg);
            RenderUtil.outline(xPosition, yPosition, xPosition + width, yPosition + height, Theme.ACCENT_DIM);
            int tw = mc.fontRendererObj.getStringWidth(displayString);
            mc.fontRendererObj.drawString(displayString, xPosition + (width - tw) / 2, yPosition + (height - 8) / 2,
                    enabled ? 0xFF111111 : Theme.MUTED, false);
        }
    }
}
