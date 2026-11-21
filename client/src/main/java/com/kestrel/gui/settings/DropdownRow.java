package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shows the current choice in a box; clicking opens a list to pick from. */
public final class DropdownRow extends Row {
    private final List<String> ids;
    private final List<String> names;
    private final Supplier<String> get;
    private final Consumer<String> set;
    private int boxX;
    private int boxY;
    private int boxW;

    public DropdownRow(String label, List<String> ids, List<String> names, Supplier<String> get, Consumer<String> set) {
        super(label);
        this.ids = ids;
        this.names = names;
        this.get = get;
        this.set = set;
    }

    private String current() {
        int i = ids.indexOf(get.get());
        return i >= 0 ? names.get(i) : "-";
    }

    @Override
    protected int controlWidth(Minecraft mc) {
        int widest = 0;
        for (String n : names) widest = Math.max(widest, mc.fontRendererObj.getStringWidth(n));
        return Math.min(Math.max(70, widest + 22), Math.max(70, w / 2));
    }

    @Override
    protected boolean active() {
        return Ui.popup() instanceof ListPopup && ((ListPopup) Ui.popup()).owner == this;
    }

    @Override
    protected void drawControl(Minecraft mc, int mx, int my, int cx, int cy) {
        boxX = cx;
        boxW = controlWidth(mc);
        boxY = cy - 8;
        boolean hov = mx >= boxX && mx < boxX + boxW && my >= boxY && my < boxY + 16;
        RenderUtil.softRect(boxX, boxY, boxX + boxW, boxY + 16, hov ? Theme.PILL_HOVER : Theme.PILL);
        RenderUtil.outline(boxX, boxY, boxX + boxW, boxY + 16, hov ? Theme.ACCENT_DIM : Theme.SECTION_BORDER);
        FontRenderer f = mc.fontRendererObj;
        f.drawString(Ui.fit(f, current(), boxW - 18), boxX + 6, cy - 4, Theme.TEXT, false);
        Ui.chevronDown(boxX + boxW - 11, cy - 1, Theme.LAVENDER);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (button != 0 || !isHovered(mx, my)) return false;
        Ui.click();
        Ui.open(new ListPopup(this));
        return true;
    }

    /** The open list. */
    static final class ListPopup implements Popup {
        private static final int ITEM_H = 14;
        private static final int MAX_VISIBLE = 8;
        final DropdownRow owner;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private int first;

        ListPopup(DropdownRow owner) {
            this.owner = owner;
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution sr = new ScaledResolution(mc);
            int widest = owner.boxW;
            for (String n : owner.names) widest = Math.max(widest, mc.fontRendererObj.getStringWidth(n) + 16);
            w = Math.min(widest, sr.getScaledWidth() - 8);
            int visible = Math.min(MAX_VISIBLE, owner.ids.size());
            h = visible * ITEM_H + 4;
            int px = Math.min(owner.boxX, sr.getScaledWidth() - w - 4);
            int below = owner.boxY + 17;
            int py = below + h <= sr.getScaledHeight() - 4 ? below : owner.boxY - 1 - h; // flip above when no room
            x = Math.max(4, px);
            y = Math.max(4, py);
            int sel = owner.ids.indexOf(owner.get.get());
            if (sel >= visible) first = Math.min(sel - visible / 2, owner.ids.size() - visible);
        }

        private int visible() {
            return Math.min(MAX_VISIBLE, owner.ids.size());
        }

        @Override
        public void draw(Minecraft mc, int mx, int my) {
            RenderUtil.softRect(x - 1, y - 1, x + w + 1, y + h + 1, 0x66000000);
            RenderUtil.softRect(x, y, x + w, y + h, 0xFA160E27);
            RenderUtil.outline(x, y, x + w, y + h, Theme.ACCENT_DIM);
            String cur = owner.get.get();
            for (int k = 0; k < visible(); k++) {
                int i = first + k;
                int iy = y + 2 + k * ITEM_H;
                boolean hov = mx >= x && mx < x + w && my >= iy && my < iy + ITEM_H;
                boolean sel = owner.ids.get(i).equals(cur);
                if (hov) RenderUtil.rect(x + 2, iy, x + w - 2, iy + ITEM_H, Theme.CARD_HOVER);
                if (sel) RenderUtil.rect(x + 2, iy + 2, x + 4, iy + ITEM_H - 2, Theme.ACCENT);
                mc.fontRendererObj.drawString(Ui.fit(mc.fontRendererObj, owner.names.get(i), w - 14), x + 8, iy + 3,
                        sel ? Theme.LAVENDER : Theme.TEXT, false);
            }
            if (owner.ids.size() > visible()) {
                int trackH = h - 4;
                int barH = Math.max(8, trackH * visible() / owner.ids.size());
                int barY = y + 2 + (trackH - barH) * first / (owner.ids.size() - visible());
                RenderUtil.rect(x + w - 3, barY, x + w - 1, barY + barH, Theme.ACCENT_DIM);
            }
        }

        @Override
        public boolean mouseClicked(int mx, int my, int button) {
            if (!contains(mx, my)) return false;
            int k = (my - y - 2) / ITEM_H;
            if (k >= 0 && k < visible()) {
                Ui.click();
                owner.set.accept(owner.ids.get(first + k));
                Ui.closePopup();
            }
            return true;
        }

        @Override
        public void mouseDragged(int mx, int my) {
        }

        @Override
        public void mouseReleased() {
        }

        @Override
        public boolean keyTyped(char c, int key) {
            return false;
        }

        @Override
        public void scroll(int direction) {
            first = Math.max(0, Math.min(owner.ids.size() - visible(), first - direction));
        }

        @Override
        public boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
