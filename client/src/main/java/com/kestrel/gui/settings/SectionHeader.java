package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.gui.widgets.Widget;
import net.minecraft.client.Minecraft;

/**
 * Title of a section card. Everything after it, up to the next header,
 * is drawn inside the same card. In search results it can link to the page
 * the setting lives on.
 */
public final class SectionHeader extends Widget {
    public final String title;
    private final Runnable link;
    private float hover;

    public SectionHeader(String title, Runnable link) {
        this.title = title;
        this.link = link;
        this.h = 20;
    }

    @Override
    public void draw(Minecraft mc, int mx, int my) {
        mc.fontRendererObj.drawString(title, x + 8, y + 7, Theme.LAVENDER, false);
        if (link != null) {
            boolean hov = isHovered(mx, my) && Ui.popup() == null;
            hover = Anim.approach(hover, hov ? 1 : 0, 18f, Ui.dt);
            String s = "Open";
            int sw = mc.fontRendererObj.getStringWidth(s);
            int col = Anim.mix(Theme.FAINT, Theme.LAVENDER, hover);
            mc.fontRendererObj.drawString(s, x + w - sw - 16, y + 7, col, false);
            Ui.chevronRight(x + w - 11, y + 8, col);
        }
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (link == null || button != 0 || !isHovered(mx, my)) return false;
        Ui.click();
        link.run();
        return true;
    }
}
