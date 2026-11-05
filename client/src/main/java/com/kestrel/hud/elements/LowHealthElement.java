package com.kestrel.hud.elements;

import com.kestrel.KestrelClient;
import com.kestrel.combat.CombatTracker;
import com.kestrel.config.ClientConfig;
import com.kestrel.hud.HudElement;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/**
 * Low Health Alert: a banner when your current target (the player you last
 * hit) drops to the threshold, plus an optional line with their health.
 * Health is the value the server sends; when the server hides real health
 * the line says so instead of showing a number (see {@link CombatTracker}).
 */
public final class LowHealthElement extends HudElement {
    private static final int PAD = 3;

    private String banner = "";
    private String line = "";
    private int bannerAlpha;
    private int lineColor;
    // Inputs the cached strings were built from (refresh runs every frame).
    private String builtName;
    private int builtState = -1;
    private float builtHealth = Float.NaN;
    private float builtMax = Float.NaN;
    private boolean builtBanner;

    public LowHealthElement(KestrelClient client) {
        super(client, "lowhealth", "Low health alert", true, 0.42f, 0.22f);
    }

    @Override
    public boolean isEnabled() {
        return opts().enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        opts().enabled = on;
    }

    private ClientConfig.LowHealthAlert opts() {
        return client.config().qol.lowHealthAlert;
    }

    @Override
    public boolean realtime() {
        return true; // banner fade
    }

    @Override
    protected void refresh(Minecraft mc) {
        ClientConfig.LowHealthAlert o = opts();
        CombatTracker t = client.combat();
        if (!o.enabled || t == null || mc.thePlayer == null || !t.hasTarget()) {
            banner = "";
            line = "";
            builtName = null;
            return;
        }
        String name = t.targetName();
        long since = t.msSinceAlert();
        boolean showBanner = since < CombatTracker.ALERT_SHOW_MS;
        if (showBanner) {
            float k = 1f - (float) since / CombatTracker.ALERT_SHOW_MS;
            bannerAlpha = Math.max(16, Math.round(255 * Math.min(1f, k * 3f)));
        }
        int state = !o.showHealth ? 0 : t.targetHealthTrusted() ? 1 : t.targetHealthProvenUntrusted() ? 2 : 3;
        float hp = t.targetHealth();
        float max = t.targetMaxHealth();
        if (name.equals(builtName) && state == builtState && showBanner == builtBanner
                && (state != 1 || (hp == builtHealth && max == builtMax))) {
            if (state == 1) lineColor = hp <= o.threshold ? (o.color | 0xFF000000) : cfg().textColor;
            return;
        }
        builtName = name;
        builtState = state;
        builtBanner = showBanner;
        builtHealth = hp;
        builtMax = max;
        banner = showBanner ? "LOW HEALTH: " + name : "";
        switch (state) {
            case 1:
                line = name + "  " + fmt(hp) + " / " + fmt(max) + " HP";
                lineColor = hp <= o.threshold ? (o.color | 0xFF000000) : cfg().textColor;
                break;
            case 2:
                line = name + "  health unavailable on this server";
                lineColor = 0xFFAAAAAA;
                break;
            case 3:
                line = name + "  checking health...";
                lineColor = 0xFFAAAAAA;
                break;
            default:
                line = "";
                break;
        }
    }

    static String fmt(float hp) {
        return String.format(java.util.Locale.ROOT, "%.1f", hp);
    }

    private static int lines(String a, String b) {
        return (a.isEmpty() ? 0 : 1) + (b.isEmpty() ? 0 : 1);
    }

    @Override
    public int width(boolean preview) {
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
        if (preview && lines(banner, line) == 0) {
            return PAD * 2 + Math.max(fr.getStringWidth("LOW HEALTH: Opponent"), fr.getStringWidth("Opponent  5.0 / 20.0 HP"));
        }
        int w = 0;
        if (!banner.isEmpty()) w = fr.getStringWidth(banner);
        if (!line.isEmpty()) w = Math.max(w, fr.getStringWidth(line));
        return w == 0 ? 0 : w + PAD * 2;
    }

    @Override
    public int height(boolean preview) {
        int n = lines(banner, line);
        if (preview && n == 0) n = 2;
        return n == 0 ? 0 : n * 10 + PAD * 2 - 1;
    }

    @Override
    protected void render(Minecraft mc, boolean preview) {
        ClientConfig.HudElementConfig c = cfg();
        ClientConfig.LowHealthAlert o = opts();
        String b = banner;
        String l = line;
        int ba = bannerAlpha;
        int lc = lineColor;
        if (preview && lines(b, l) == 0) {
            b = "LOW HEALTH: Opponent";
            l = "Opponent  5.0 / 20.0 HP";
            ba = 255;
            lc = o.color | 0xFF000000;
        }
        int w = width(preview);
        int h = height(preview);
        if (w <= 0) return;
        if (c.background) RenderUtil.softRect(0, 0, w, h, 0x80000000);
        FontRenderer fr = mc.fontRendererObj;
        int y = PAD;
        if (!b.isEmpty()) {
            // Alpha below ~4 makes the font renderer draw fully opaque, hence the floor in refresh.
            fr.drawString(b, PAD + (w - PAD * 2 - fr.getStringWidth(b)) / 2, y, (ba << 24) | (o.color & 0xFFFFFF), c.textShadow);
            y += 10;
        }
        if (!l.isEmpty()) fr.drawString(l, PAD, y, lc, c.textShadow);
    }
}
