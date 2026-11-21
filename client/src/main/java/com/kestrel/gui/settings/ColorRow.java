package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A colour swatch with its hex code; clicking opens a picker (palette, hue/saturation/brightness, hex). */
public final class ColorRow extends Row {
    private static final int CW = 66;
    private final Supplier<Integer> get;
    private final Consumer<Integer> set;
    private int boxX;
    private int boxY;

    public ColorRow(String label, Supplier<Integer> get, Consumer<Integer> set) {
        super(label);
        this.get = get;
        this.set = set;
    }

    static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    @Override
    protected int controlWidth(Minecraft mc) {
        return CW;
    }

    @Override
    protected boolean active() {
        return Ui.popup() instanceof Picker && ((Picker) Ui.popup()).owner == this;
    }

    @Override
    protected void drawControl(Minecraft mc, int mx, int my, int cx, int cy) {
        boxX = cx;
        boxY = cy - 8;
        boolean hov = mx >= boxX && mx < boxX + CW && my >= boxY && my < boxY + 16;
        RenderUtil.softRect(boxX, boxY, boxX + CW, boxY + 16, hov ? Theme.PILL_HOVER : Theme.PILL);
        RenderUtil.outline(boxX, boxY, boxX + CW, boxY + 16, hov ? Theme.ACCENT_DIM : Theme.SECTION_BORDER);
        int c = get.get() | 0xFF000000;
        RenderUtil.rect(boxX + 3, boxY + 3, boxX + 13, boxY + 13, 0xFF000000);
        RenderUtil.rect(boxX + 4, boxY + 4, boxX + 12, boxY + 12, c);
        mc.fontRendererObj.drawString(hex(c), boxX + 17, cy - 4, Theme.MUTED, false);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (button != 0 || !isHovered(mx, my)) return false;
        Ui.click();
        Ui.open(new Picker(this));
        return true;
    }

    /** The colour picker popup. */
    static final class Picker implements Popup {
        private static final int SW = 14;
        private static final int GAP = 2;
        private static final int COLS = 8;
        private static final int BAR_H = 8;
        final ColorRow owner;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final float[] hsb = new float[3];
        private int dragBar = -1;
        private String hexText;
        private boolean hexFocused;

        Picker(ColorRow owner) {
            this.owner = owner;
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution sr = new ScaledResolution(mc);
            w = COLS * (SW + GAP) - GAP + 12;
            int rows = (Theme.PALETTE.length + COLS - 1) / COLS;
            h = 6 + rows * (SW + GAP) + 3 * (BAR_H + 10) + 22;
            int px = Math.min(owner.boxX + 66 - w, sr.getScaledWidth() - w - 4);
            int below = owner.boxY + 17;
            int py = below + h <= sr.getScaledHeight() - 4 ? below : owner.boxY - 1 - h;
            x = Math.max(4, px);
            y = Math.max(4, py);
            int c = owner.get.get();
            java.awt.Color.RGBtoHSB(c >> 16 & 255, c >> 8 & 255, c & 255, hsb);
            hexText = hex(c).substring(1);
        }

        private int barsY() {
            int rows = (Theme.PALETTE.length + COLS - 1) / COLS;
            return y + 6 + rows * (SW + GAP) + 4;
        }

        private void apply(int rgb) {
            owner.set.accept(rgb | 0xFF000000);
            hexText = hex(rgb).substring(1);
        }

        private void applyHsb() {
            apply(java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF);
        }

        @Override
        public void draw(Minecraft mc, int mx, int my) {
            FontRenderer f = mc.fontRendererObj;
            RenderUtil.softRect(x - 1, y - 1, x + w + 1, y + h + 1, 0x66000000);
            RenderUtil.softRect(x, y, x + w, y + h, 0xFA160E27);
            RenderUtil.outline(x, y, x + w, y + h, Theme.ACCENT_DIM);
            int cur = owner.get.get() | 0xFF000000;
            for (int i = 0; i < Theme.PALETTE.length; i++) {
                int sx = x + 6 + (i % COLS) * (SW + GAP);
                int sy = y + 6 + (i / COLS) * (SW + GAP);
                boolean hov = mx >= sx && mx < sx + SW && my >= sy && my < sy + SW;
                int border = Theme.PALETTE[i] == cur ? Theme.LAVENDER : (hov ? Theme.MUTED : 0xFF000000);
                RenderUtil.rect(sx - 1, sy - 1, sx + SW + 1, sy + SW + 1, border);
                RenderUtil.rect(sx, sy, sx + SW, sy + SW, Theme.PALETTE[i]);
            }
            int by = barsY();
            int bw = w - 12;
            String[] names = {"Hue", "Saturation", "Brightness"};
            for (int b = 0; b < 3; b++) {
                int yy = by + b * (BAR_H + 10);
                f.drawString(names[b], x + 6, yy, Theme.FAINT, false);
                int ty = yy + 9;
                // Gradient as 2 px columns (only while the picker is open).
                for (int px = 0; px < bw; px += 2) {
                    float t = px / (float) (bw - 1);
                    float hh = b == 0 ? t : hsb[0];
                    float ss = b == 1 ? t : (b == 0 ? 1f : hsb[1]);
                    float vv = b == 2 ? t : (b == 0 ? 1f : hsb[2]);
                    int col = java.awt.Color.HSBtoRGB(hh, ss, vv) | 0xFF000000;
                    RenderUtil.rect(x + 6 + px, ty, x + 6 + Math.min(bw, px + 2), ty + BAR_H, col);
                }
                int kx = x + 6 + Math.round(hsb[b] * (bw - 1));
                RenderUtil.rect(kx - 2, ty - 2, kx + 2, ty + BAR_H + 2, 0xFF000000);
                RenderUtil.rect(kx - 1, ty - 1, kx + 1, ty + BAR_H + 1, 0xFFFFFFFF);
            }
            // Hex field and preview.
            int fy = y + h - 20;
            RenderUtil.rect(x + 6, fy, x + 22, fy + 14, 0xFF000000);
            RenderUtil.rect(x + 7, fy + 1, x + 21, fy + 13, cur);
            int fx = x + 26;
            RenderUtil.softRect(fx, fy, x + w - 6, fy + 14, Theme.PILL);
            RenderUtil.outline(fx, fy, x + w - 6, fy + 14, hexFocused ? Theme.ACCENT : Theme.SECTION_BORDER);
            boolean caret = hexFocused && (Ui.now / 500) % 2 == 0;
            f.drawString("#" + hexText + (caret ? "_" : ""), fx + 5, fy + 3, hexFocused ? Theme.TEXT : Theme.MUTED, false);
        }

        @Override
        public boolean mouseClicked(int mx, int my, int button) {
            if (!contains(mx, my)) {
                return false;
            }
            hexFocused = false;
            for (int i = 0; i < Theme.PALETTE.length; i++) {
                int sx = x + 6 + (i % COLS) * (SW + GAP);
                int sy = y + 6 + (i / COLS) * (SW + GAP);
                if (mx >= sx && mx < sx + SW && my >= sy && my < sy + SW) {
                    Ui.click();
                    int c = Theme.PALETTE[i];
                    java.awt.Color.RGBtoHSB(c >> 16 & 255, c >> 8 & 255, c & 255, hsb);
                    apply(c & 0xFFFFFF);
                    return true;
                }
            }
            int by = barsY();
            for (int b = 0; b < 3; b++) {
                int ty = by + b * (BAR_H + 10) + 9;
                if (my >= ty - 3 && my < ty + BAR_H + 3) {
                    dragBar = b;
                    mouseDragged(mx, my);
                    return true;
                }
            }
            int fy = y + h - 20;
            if (my >= fy && my < fy + 14 && mx >= x + 26) hexFocused = true;
            return true;
        }

        @Override
        public void mouseDragged(int mx, int my) {
            if (dragBar < 0) return;
            float t = (mx - x - 6) / (float) (w - 13);
            hsb[dragBar] = Math.max(0f, Math.min(1f, t));
            applyHsb();
        }

        @Override
        public void mouseReleased() {
            dragBar = -1;
        }

        @Override
        public boolean keyTyped(char c, int key) {
            if (!hexFocused) return false;
            if (key == Keyboard.KEY_BACK) {
                if (!hexText.isEmpty()) hexText = hexText.substring(0, hexText.length() - 1);
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                hexFocused = false;
            } else if (Character.digit(c, 16) >= 0 && hexText.length() < 6) {
                hexText = (hexText + c).toUpperCase(Locale.ROOT);
            } else {
                return true;
            }
            if (hexText.length() == 6) {
                int rgb = Integer.parseInt(hexText, 16);
                java.awt.Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, hsb);
                owner.set.accept(rgb | 0xFF000000);
            }
            return true;
        }

        @Override
        public void scroll(int direction) {
        }

        @Override
        public boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
