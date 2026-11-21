package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * Edits a real Minecraft keybind (the same one shown under Controls). Click,
 * then press a key or mouse button; Backspace or Delete unbinds, Escape
 * cancels. Saved to options.txt like any vanilla binding.
 */
public final class KeybindRow extends Row {
    private static final int CW = 80;
    private final KeyBinding binding;
    private boolean listening;
    /** The click that started listening must not become the binding. */
    private long listenSince;

    public KeybindRow(String label, KeyBinding binding) {
        super(label);
        this.binding = binding;
    }

    public boolean isListening() {
        return listening;
    }

    @Override
    protected int controlWidth(Minecraft mc) {
        return CW;
    }

    @Override
    protected boolean active() {
        return listening;
    }

    private boolean conflicts() {
        int code = binding.getKeyCode();
        if (code == 0) return false;
        for (KeyBinding other : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (other != binding && other.getKeyCode() == code) return true;
        }
        return false;
    }

    @Override
    protected void drawControl(Minecraft mc, int mx, int my, int cx, int cy) {
        int by = cy - 8;
        boolean hov = mx >= cx && mx < cx + CW && my >= by && my < by + 16;
        int bg = listening ? Theme.SIDEBAR_SELECTED : (hov ? Theme.PILL_HOVER : Theme.PILL);
        RenderUtil.softRect(cx, by, cx + CW, by + 16, bg);
        float pulse = listening ? 0.5f + 0.5f * (float) Math.sin(Ui.now / 160.0) : 0f;
        int border = listening ? Anim.mix(Theme.ACCENT_DIM, Theme.LAVENDER, pulse) : (hov ? Theme.ACCENT_DIM : Theme.SECTION_BORDER);
        RenderUtil.outline(cx, by, cx + CW, by + 16, border);
        String s;
        int col;
        if (listening) {
            s = "Press a key";
            col = Theme.LAVENDER;
        } else {
            s = binding.getKeyCode() == 0 ? "None" : GameSettings.getKeyDisplayString(binding.getKeyCode());
            col = conflicts() ? Theme.WARN : (binding.getKeyCode() == 0 ? Theme.FAINT : Theme.TEXT);
        }
        s = Ui.fit(mc.fontRendererObj, s, CW - 8);
        mc.fontRendererObj.drawString(s, cx + (CW - mc.fontRendererObj.getStringWidth(s)) / 2, cy - 4, col, false);
        if (!listening && hov && conflicts()) Ui.tooltip("Also used by another control (see Controls).", mx, my);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (listening) {
            if (Ui.now - listenSince > 150) bind(button - 100);
            return true;
        }
        if (button != 0 || !isHovered(mx, my)) return false;
        Ui.click();
        listening = true;
        listenSince = Ui.now;
        return true;
    }

    /** A key while listening. @return true if used */
    public boolean keyTyped(char c, int key) {
        if (!listening) return false;
        if (key == Keyboard.KEY_ESCAPE) {
            listening = false;
        } else if (key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE) {
            bind(Keyboard.KEY_NONE);
        } else if (key != 0) {
            bind(key);
        }
        return true;
    }

    public void cancel() {
        listening = false;
    }

    private void bind(int code) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.setOptionKeyBinding(binding, code);
        KeyBinding.resetKeyBindingArrayAndHash();
        mc.gameSettings.saveOptions();
        listening = false;
    }
}
