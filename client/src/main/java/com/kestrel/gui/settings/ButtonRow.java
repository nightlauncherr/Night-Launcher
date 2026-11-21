package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;

/**
 * An action: the whole row is clickable. {@code primary} actions get an
 * accent button on the right; others show a chevron.
 */
public final class ButtonRow extends Row {
    private final String button;
    private final boolean primary;
    private final Runnable action;
    private boolean selected;

    /**
     * @param label  what it does
     * @param button text of the button on the right ("Open", "Preview", ...), or null for a chevron
     */
    public ButtonRow(String label, String button, boolean primary, Runnable action) {
        super(label);
        this.button = button;
        this.primary = primary;
        this.action = action;
    }

    /** Marks the row as the current choice (e.g. the active preset). */
    public ButtonRow selected(boolean on) {
        selected = on;
        return this;
    }

    @Override
    protected int controlWidth(Minecraft mc) {
        return button == null ? (selected ? 46 : 8) : Math.max(52, mc.fontRendererObj.getStringWidth(button) + 16);
    }

    @Override
    protected void drawControl(Minecraft mc, int mx, int my, int cx, int cy) {
        int cw = controlWidth(mc);
        if (button == null) {
            if (selected) {
                RenderUtil.softRect(cx, cy - 6, cx + cw, cy + 6, Theme.SIDEBAR_SELECTED);
                String s = "Active";
                mc.fontRendererObj.drawString(s, cx + (cw - mc.fontRendererObj.getStringWidth(s)) / 2, cy - 4, Theme.LAVENDER, false);
            } else {
                Ui.chevronRight(cx + cw - 4, cy - 2, Anim.mix(Theme.FAINT, Theme.LAVENDER, hover));
            }
            return;
        }
        int bg = primary ? Anim.mix(Theme.ACCENT, Theme.ACCENT_HOVER, hover) : Anim.mix(Theme.CARD, Theme.CARD_HOVER, hover);
        RenderUtil.softRect(cx, cy - 8, cx + cw, cy + 8, bg);
        if (!primary) RenderUtil.outline(cx, cy - 8, cx + cw, cy + 8, Anim.mix(Theme.SECTION_BORDER, Theme.ACCENT_DIM, hover));
        mc.fontRendererObj.drawString(button, cx + (cw - mc.fontRendererObj.getStringWidth(button)) / 2, cy - 4,
                primary ? 0xFF140B22 : Theme.TEXT, false);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int b) {
        if (b != 0 || !isHovered(mx, my)) return false;
        Ui.click();
        action.run();
        return true;
    }
}
