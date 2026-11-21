package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.gui.widgets.Widget;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import java.util.Collections;
import java.util.List;

/**
 * One setting inside a section card: its name, an optional description
 * underneath, and a control on the right (drawn by the subclass).
 */
public abstract class Row extends Widget {
    protected static final int PAD = 8;
    public final String label;
    private String description;
    private List<String> lines = Collections.emptyList();
    private int wrappedFor = -1;
    protected float hover;

    protected Row(String label) {
        this.label = label;
    }

    public String description() {
        return description;
    }

    /** Set (or append to) the explanation shown under the name. */
    public void describe(String text) {
        description = description == null ? text : description + " " + text;
        wrappedFor = -1;
    }

    /** Width of the control area on the right, in GUI pixels. */
    protected abstract int controlWidth(Minecraft mc);

    /** Lay out for a width; returns the height. */
    public int layout(Minecraft mc, int width) {
        w = width;
        if (description != null) {
            int textW = Math.max(60, width - 2 * PAD - controlWidth(mc) - 8);
            if (wrappedFor != textW) {
                lines = mc.fontRendererObj.listFormattedStringToWidth(description, textW);
                wrappedFor = textW;
            }
            h = 19 + lines.size() * 10 + 3;
        } else {
            h = 22;
        }
        return h;
    }

    /** Vertical centre of the control. */
    protected int controlCenterY() {
        return description == null ? y + h / 2 : y + 11;
    }

    @Override
    public final void draw(Minecraft mc, int mx, int my) {
        boolean hov = isHovered(mx, my) && Ui.popup() == null;
        hover = Anim.approach(hover, hov || active() ? 1f : 0f, 18f, Ui.dt);
        if (hover > 0.01f) RenderUtil.rect(x, y, x + w, y + h, Anim.fade(Theme.ROW_HOVER, hover));
        FontRenderer f = mc.fontRendererObj;
        int labelW = w - 2 * PAD - controlWidth(mc) - 6;
        String shown = Ui.fit(f, label, labelW);
        int ly = description == null ? y + (h - 8) / 2 : y + 7;
        f.drawString(shown, x + PAD, ly, enabledLook() ? Theme.TEXT : Theme.MUTED, false);
        if (hov && !shown.equals(label)) Ui.tooltip(label, mx, my);
        for (int i = 0; i < lines.size(); i++) {
            f.drawString(lines.get(i), x + PAD, y + 19 + i * 10, Theme.MUTED, false);
        }
        drawControl(mc, mx, my, x + w - PAD - controlWidth(mc), controlCenterY());
    }

    /** Draw the control; {@code cx} is its left edge, {@code cy} its vertical centre. */
    protected abstract void drawControl(Minecraft mc, int mx, int my, int cx, int cy);

    /** Kept highlighted (e.g. a keybind waiting for a key). */
    protected boolean active() {
        return false;
    }

    protected boolean enabledLook() {
        return true;
    }

    /** Text the search box matches against (name and description). */
    public String searchDescription() {
        return description == null ? "" : description;
    }
}
