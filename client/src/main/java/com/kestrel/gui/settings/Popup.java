package com.kestrel.gui.settings;

import net.minecraft.client.Minecraft;

/** A floating panel above the settings (dropdown list, colour picker). */
public interface Popup {
    void draw(Minecraft mc, int mouseX, int mouseY);

    /** @return true if the click hit the popup (a click elsewhere closes it) */
    boolean mouseClicked(int mouseX, int mouseY, int button);

    void mouseDragged(int mouseX, int mouseY);

    void mouseReleased();

    /** @return true if the key was used */
    boolean keyTyped(char c, int key);

    /** Mouse wheel over the popup: +1 up, -1 down. */
    void scroll(int direction);

    boolean contains(int mouseX, int mouseY);
}
