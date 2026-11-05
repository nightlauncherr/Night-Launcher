package com.kestrel.hud.elements;

import com.kestrel.KestrelClient;
import com.kestrel.combat.CombatTracker;
import com.kestrel.combat.HealthEstimator;
import com.kestrel.config.ClientConfig;
import com.kestrel.hud.HudElement;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/**
 * Opponent Health Prediction: {@code Opponent HP: 14.0} when the server's
 * value is confirmed real, {@code Opponent HP: ~8.5} (in the estimate colour)
 * when it is estimated from your confirmed hits, and "unavailable" when there
 * is nothing trustworthy to show. A value the server sent but that has not yet
 * been shown to be real is marked with "?".
 */
public final class OpponentHealthElement extends HudElement {
    private static final int PAD = 3;
    private static final String PREVIEW = "Opponent HP: ~8.5";

    private String text = "";
    private int color;
    private int textWidth;
    private HealthEstimator.Source builtSource;
    private float builtHealth = Float.NaN;
    private float builtAbsorption = Float.NaN;

    public OpponentHealthElement(KestrelClient client) {
        super(client, "opponenthp", "Opponent health", true, 0.42f, 0.36f);
    }

    @Override
    public boolean isEnabled() {
        return client.config().qol.healthPrediction.enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        client.config().qol.healthPrediction.enabled = on;
    }

    @Override
    protected void refresh(Minecraft mc) {
        ClientConfig.Qol q = client.config().qol;
        CombatTracker t = client.combat();
        if (!q.healthPrediction.enabled || t == null || mc.thePlayer == null || !t.hasTarget()) {
            text = "";
            builtSource = null;
            return;
        }
        HealthEstimator est = t.estimator();
        ClientConfig.HudElementConfig c = cfg();
        HealthEstimator.Source src = est.source();
        float hp = est.health();
        float abs = est.absorption();
        if (src == builtSource && hp == builtHealth && abs == builtAbsorption) {
            // Unchanged numbers (this runs every frame): only colours can have changed.
            color = src == HealthEstimator.Source.CONFIRMED ? c.textColor
                    : src == HealthEstimator.Source.ESTIMATED ? q.healthPrediction.estimateColor | 0xFF000000 : 0xFFAAAAAA;
            return;
        }
        builtSource = src;
        builtHealth = hp;
        builtAbsorption = abs;
        String value;
        switch (src) {
            case CONFIRMED:
                value = withAbsorption(LowHealthElement.fmt(hp), abs);
                color = c.textColor;
                break;
            case ESTIMATED:
                value = "~" + withAbsorption(LowHealthElement.fmt(hp), abs);
                color = q.healthPrediction.estimateColor | 0xFF000000;
                break;
            case REPORTED:
                value = withAbsorption(LowHealthElement.fmt(hp), abs) + "?";
                color = 0xFFAAAAAA;
                break;
            default:
                value = "unavailable";
                color = 0xFFAAAAAA;
                break;
        }
        text = "Opponent HP: " + value;
        textWidth = mc.fontRendererObj.getStringWidth(text);
    }

    private static String withAbsorption(String hp, float absorption) {
        return absorption > 0.05f ? hp + " +" + LowHealthElement.fmt(absorption) : hp;
    }

    @Override
    public boolean realtime() {
        return true;
    }

    @Override
    public int width(boolean preview) {
        if (preview && text.isEmpty()) return Minecraft.getMinecraft().fontRendererObj.getStringWidth(PREVIEW) + PAD * 2;
        return text.isEmpty() ? 0 : textWidth + PAD * 2;
    }

    @Override
    public int height(boolean preview) {
        return !preview && text.isEmpty() ? 0 : 10 + PAD * 2 - 1;
    }

    @Override
    protected void render(Minecraft mc, boolean preview) {
        boolean sample = preview && text.isEmpty();
        String t = sample ? PREVIEW : text;
        if (t.isEmpty()) return;
        ClientConfig.HudElementConfig c = cfg();
        if (c.background) RenderUtil.softRect(0, 0, width(preview), height(preview), 0x80000000);
        FontRenderer fr = mc.fontRendererObj;
        int col = sample ? client.config().qol.healthPrediction.estimateColor | 0xFF000000 : color;
        fr.drawString(t, PAD, PAD, col, c.textShadow);
    }
}
