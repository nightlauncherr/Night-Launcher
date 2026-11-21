package com.kestrel.gui.settings;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Slider with its current value shown next to it; drag or click the track. */
public final class SliderRow extends Row {
    private static final int VALUE_W = 44;
    private final float min;
    private final float max;
    private final float step;
    private final Supplier<Float> get;
    private final Consumer<Float> set;
    private final Function<Float, String> format;
    private boolean dragging;
    private float shownFor = Float.NaN;
    private String shown = "";
    private float knobF = -1;
    private int trackX;
    private int trackW;

    public SliderRow(String label, float min, float max, float step, Supplier<Float> get, Consumer<Float> set,
                     Function<Float, String> format) {
        super(label);
        this.min = min;
        this.max = max;
        this.step = step;
        this.get = get;
        this.set = set;
        this.format = format;
    }

    @Override
    protected int controlWidth(Minecraft mc) {
        return Math.max(90, Math.min(150, w / 3)) + VALUE_W;
    }

    @Override
    protected boolean active() {
        return dragging;
    }

    @Override
    protected void drawControl(Minecraft mc, int mx, int my, int cx, int cy) {
        float v = get.get();
        if (v != shownFor) {
            shown = format.apply(v); // formatted only when the value changes
            shownFor = v;
        }
        trackX = cx;
        trackW = controlWidth(mc) - VALUE_W - 6;
        float f = (v - min) / (max - min);
        f = Math.max(0, Math.min(1, f));
        if (knobF < 0 || dragging) knobF = f;
        knobF = Anim.approach(knobF, f, 25f, Ui.dt);
        int fill = trackX + Math.round(trackW * knobF);
        RenderUtil.softRect(trackX, cy - 2, trackX + trackW, cy + 2, Theme.TRACK);
        RenderUtil.softRect(trackX, cy - 2, Math.max(trackX + 2, fill), cy + 2, Theme.ACCENT);
        boolean big = dragging || hover > 0.5f;
        int r = big ? 5 : 4;
        RenderUtil.softRect(fill - r, cy - r, fill + r, cy + r, 0xFFFFFFFF);
        RenderUtil.softRect(fill - r + 2, cy - r + 2, fill + r - 2, cy + r - 2, Theme.LAVENDER);
        int vx = trackX + trackW + 6;
        RenderUtil.softRect(vx, cy - 7, vx + VALUE_W, cy + 7, Theme.PILL);
        String s = Ui.fit(mc.fontRendererObj, shown, VALUE_W - 4);
        mc.fontRendererObj.drawString(s, vx + (VALUE_W - mc.fontRendererObj.getStringWidth(s)) / 2, cy - 4, Theme.LAVENDER, false);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int button) {
        if (button != 0 || !isHovered(mx, my)) return false;
        if (mx < trackX - 6 || mx > trackX + trackW + 6) return false;
        dragging = true;
        update(mx);
        return true;
    }

    @Override
    public void mouseDragged(int mx, int my) {
        if (dragging) update(mx);
    }

    @Override
    public void mouseReleased(int mx, int my) {
        dragging = false;
    }

    private void update(int mx) {
        float f = (mx - trackX) / (float) Math.max(1, trackW);
        f = Math.max(0, Math.min(1, f));
        float v = min + f * (max - min);
        if (step > 0) v = Math.round(v / step) * step;
        v = Math.max(min, Math.min(max, v));
        if (Math.abs(v - get.get()) > 1e-6f) set.accept(v);
    }
}
