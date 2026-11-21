package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Per-frame state shared by the settings widgets: frame time (for
 * animations), the open popup (dropdown list, colour picker) and the tooltip
 * to draw on top. Only the settings screen uses it, on the client thread.
 */
public final class Ui {
    /** Seconds since the previous frame (clamped), for {@link Anim#approach}. */
    public static float dt;
    /** Wall clock of the current frame, ms. */
    public static long now;

    private static Popup popup;
    private static String tooltip;
    private static int tooltipX;
    private static int tooltipY;

    private Ui() {
    }

    public static void beginFrame(long nowMs, long lastMs) {
        now = nowMs;
        dt = lastMs == 0 ? 0f : Math.min(0.1f, Math.max(0f, (nowMs - lastMs) / 1000f));
        tooltip = null;
    }

    // ---- popup ----------------------------------------------------------

    public static void open(Popup p) {
        popup = p;
    }

    public static void closePopup() {
        popup = null;
    }

    public static Popup popup() {
        return popup;
    }

    // ---- tooltip --------------------------------------------------------

    /** Ask for a tooltip this frame (the last request wins). */
    public static void tooltip(String text, int mx, int my) {
        tooltip = text;
        tooltipX = mx;
        tooltipY = my;
    }

    /** Draw the requested tooltip, kept inside the screen. */
    public static void drawTooltip(Minecraft mc, int screenW, int screenH) {
        if (tooltip == null || tooltip.isEmpty()) return;
        FontRenderer f = mc.fontRendererObj;
        int maxW = Math.min(220, screenW - 16);
        List<String> lines = f.listFormattedStringToWidth(tooltip, maxW);
        int w = 0;
        for (String l : lines) w = Math.max(w, f.getStringWidth(l));
        int h = lines.size() * 10 + 6;
        int x = tooltipX + 10;
        int y = tooltipY + 10;
        if (x + w + 10 > screenW - 4) x = tooltipX - w - 14;
        if (x < 4) x = 4;
        if (y + h > screenH - 4) y = screenH - 4 - h;
        if (y < 4) y = 4;
        RenderUtil.softRect(x, y, x + w + 10, y + h, 0xF6150D24);
        RenderUtil.outline(x, y, x + w + 10, y + h, Theme.ACCENT_DIM);
        for (int i = 0; i < lines.size(); i++) f.drawString(lines.get(i), x + 5, y + 4 + i * 10, Theme.TEXT, false);
    }

    // ---- small helpers --------------------------------------------------

    public static void click() {
        Minecraft.getMinecraft().getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    /** {@code s} cut with "..." to fit {@code width}. */
    public static String fit(FontRenderer f, String s, int width) {
        if (f.getStringWidth(s) <= width) return s;
        return f.trimStringToWidth(s, Math.max(0, width - 6)) + "...";
    }

    /** A small downward chevron at (x, y), 5 px wide. */
    public static void chevronDown(int x, int y, int color) {
        RenderUtil.rect(x, y, x + 5, y + 1, color);
        RenderUtil.rect(x + 1, y + 1, x + 4, y + 2, color);
        RenderUtil.rect(x + 2, y + 2, x + 3, y + 3, color);
    }

    /** A small right-pointing chevron at (x, y), 3 px wide, 5 px tall. */
    public static void chevronRight(int x, int y, int color) {
        RenderUtil.rect(x, y, x + 1, y + 5, color);
        RenderUtil.rect(x + 1, y + 1, x + 2, y + 4, color);
        RenderUtil.rect(x + 2, y + 2, x + 3, y + 3, color);
    }
}
