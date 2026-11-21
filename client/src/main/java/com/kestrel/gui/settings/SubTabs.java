package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.gui.widgets.Widget;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.IntConsumer;

/** Segmented control for the pages of a category (Capes / Kill effects / ...). */
public final class SubTabs extends Widget {
    private final List<String> labels;
    private final int selected;
    private final IntConsumer onSelect;
    private final float[] hover;
    private float slide = -1;

    public SubTabs(List<String> labels, int selected, IntConsumer onSelect) {
        this.labels = labels;
        this.selected = selected;
        this.onSelect = onSelect;
        this.hover = new float[labels.size()];
        this.h = 20;
    }

    private int tabW() {
        return (w - 4) / Math.max(1, labels.size());
    }

    @Override
    public void draw(Minecraft mc, int mx, int my) {
        RenderUtil.softRect(x, y, x + w, y + h, Theme.PILL);
        int tw = tabW();
        if (slide < 0) slide = selected;
        slide = Anim.approach(slide, selected, 20f, Ui.dt);
        int sx = x + 2 + Math.round(slide * tw);
        RenderUtil.softRect(sx, y + 2, sx + tw, y + h - 2, Theme.SIDEBAR_SELECTED);
        RenderUtil.rect(sx + 4, y + h - 3, sx + tw - 4, y + h - 2, Theme.ACCENT);
        for (int i = 0; i < labels.size(); i++) {
            int tx = x + 2 + i * tw;
            boolean hov = mx >= tx && mx < tx + tw && my >= y && my < y + h && Ui.popup() == null;
            hover[i] = Anim.approach(hover[i], hov ? 1 : 0, 18f, Ui.dt);
            String s = Ui.fit(mc.fontRendererObj, labels.get(i), tw - 6);
            int col = i == selected ? Theme.TEXT : Anim.mix(Theme.MUTED, Theme.TEXT, hover[i]);
            mc.fontRendererObj.drawString(s, tx + (tw - mc.fontRendererObj.getStringWidth(s)) / 2, y + 6, col, false);
        }
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (button != 0 || !isHovered(mx, my)) return false;
        int i = (mx - x - 2) / Math.max(1, tabW());
        if (i < 0 || i >= labels.size() || i == selected) return i >= 0 && i < labels.size();
        Ui.click();
        onSelect.accept(i);
        return true;
    }
}
