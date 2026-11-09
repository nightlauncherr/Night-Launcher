package com.kestrel.openmap;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Adds CREATE OPEN MAP WORLD next to CREATE SUMO WORLD at the top left of the Singleplayer list. */
public final class OpenMapMenuButton {
    static final int BUTTON_ID = 0x5A41;
    static final String LABEL = "CREATE OPEN MAP WORLD";

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiSelectWorld)) return;
        event.buttonList.add(new Button(BUTTON_ID, 142, 6, 150, 20, LABEL));
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.gui instanceof GuiSelectWorld) || event.button.id != BUTTON_ID) return;
        event.setCanceled(true);
        event.button.enabled = false;
        OpenMapWorlds.open(Minecraft.getMinecraft(), event.gui);
    }

    static final class Button extends GuiButton {
        Button(int id, int x, int y, int w, int h, String text) {
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
