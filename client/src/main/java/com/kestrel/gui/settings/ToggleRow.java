package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** On/off switch; the whole row is clickable and the knob slides. */
public final class ToggleRow extends Row {
    private static final int SW = 24;
    private static final int SH = 12;
    private final Supplier<Boolean> get;
    private final Consumer<Boolean> set;
    private float knob = -1;

    public ToggleRow(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        super(label);
        this.get = get;
        this.set = set;
    }

    @Override
    protected int controlWidth(Minecraft mc) {
        return SW;
    }

    @Override
    protected void drawControl(Minecraft mc, int mx, int my, int cx, int cy) {
        boolean on = get.get();
        if (knob < 0) knob = on ? 1 : 0;
        knob = Anim.approach(knob, on ? 1 : 0, 22f, Ui.dt);
        int sy = cy - SH / 2;
        int track = Anim.mix(Theme.TRACK, Theme.ACCENT, knob);
        RenderUtil.softRect(cx, sy, cx + SW, sy + SH, track);
        int kx = cx + 1 + Math.round(knob * (SW - SH));
        RenderUtil.softRect(kx, sy + 1, kx + SH - 2, sy + SH - 1, Anim.mix(0xFFCFC3E0, 0xFFFFFFFF, knob));
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (button != 0 || !isHovered(mx, my)) return false;
        Ui.click();
        set.accept(!get.get());
        return true;
    }
}
