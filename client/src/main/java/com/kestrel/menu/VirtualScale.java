package com.kestrel.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Keeps Kestrel's full-screen menus in proportion at every GUI scale.
 *
 * <p>The screen is laid out on a virtual canvas about {@link #TARGET} units tall:
 * when the GUI scale leaves more room than that (Small or Normal on a large
 * window), everything is drawn with a whole-number zoom, so text stays
 * pixel-crisp and the logo, menu, character and buttons keep the same
 * proportions. Setting the screen's width/height to the virtual size also makes
 * vanilla's mouse handling report virtual coordinates.
 */
public final class VirtualScale {
    /** Virtual canvas height the layouts are designed around. */
    public static final float TARGET = 260f;

    private VirtualScale() {
    }

    /** Resize {@code screen} to the virtual canvas; returns the zoom (1, 2, 3...). */
    public static int apply(GuiScreen screen, Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int k = factor(sr.getScaledHeight());
        screen.width = sr.getScaledWidth() / k;
        screen.height = sr.getScaledHeight() / k;
        return k;
    }

    /** Zoom for a GUI-scaled screen height (pure, unit-tested). */
    static int factor(int scaledHeight) {
        return Math.max(1, (int) (scaledHeight / TARGET));
    }
}
