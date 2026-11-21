package com.kestrel.gui.settings;

import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;

/** Explanatory text inside a card (not attached to one setting). */
public final class NoteRow extends com.kestrel.gui.widgets.Widget {
    private final String text;
    private final int color;
    private List<String> lines = Collections.emptyList();
    private int wrappedFor = -1;

    public NoteRow(String text, int color) {
        this.text = text;
        this.color = color;
    }

    public String text() {
        return text;
    }

    public int layout(Minecraft mc, int width) {
        w = width;
        int tw = Math.max(40, width - 16);
        if (wrappedFor != tw) {
            lines = mc.fontRendererObj.listFormattedStringToWidth(text, tw);
            wrappedFor = tw;
        }
        h = lines.size() * 10 + 8;
        return h;
    }

    @Override
    public void draw(Minecraft mc, int mx, int my) {
        for (int i = 0; i < lines.size(); i++) mc.fontRendererObj.drawString(lines.get(i), x + 8, y + 4 + i * 10, color, false);
    }
}
