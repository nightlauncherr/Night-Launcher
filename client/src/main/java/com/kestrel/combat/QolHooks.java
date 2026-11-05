package com.kestrel.combat;

import com.kestrel.KestrelClient;
import com.kestrel.KestrelMod;
import com.kestrel.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;

/**
 * Static entry points called from Kestrel's mixins for the QoL features. Every
 * method is null-safe (it returns vanilla behaviour before Kestrel has started)
 * and cheap when its feature is off. Only presentation is changed here: none
 * of these hooks sends packets, moves the player or alters game state.
 */
public final class QolHooks {
    private QolHooks() {
    }

    private static ClientConfig.Qol qol() {
        KestrelClient c = KestrelMod.client();
        return c == null ? null : c.config().qol;
    }

    // ---- Hit Delay Fix -----------------------------------------------------------

    /**
     * Vanilla sets a 10-tick (0.5 s) left-click lockout after a click that hits
     * nothing (air) in survival. It is purely client-side input throttling; the
     * server never sees it. With the fix on the lockout is 0, so the next click
     * swings immediately. Attack packets are only ever sent for clicks on an
     * entity, which this lockout never applied to, so no attack is added.
     */
    public static int missLockout(int vanilla) {
        ClientConfig.Qol q = qol();
        return q != null && q.hitDelayFix.enabled && q.hitDelayFix.removeMissLock ? 0 : vanilla;
    }

    /**
     * Vanilla only restarts the arm-swing animation once the current swing is
     * at least half done, so quick clicks show one long swing for several
     * attacks. For the local player with the fix on, the swing restarts on
     * every click (after the first tick of a swing). Animation only: the swing
     * packet is sent by {@code EntityPlayerSP.swingItem} on every click either way.
     *
     * @param animationEnd the entity's swing length in ticks (6 without Haste or Mining Fatigue)
     */
    public static int swingRestartDivisor(EntityLivingBase e, int vanilla, int animationEnd) {
        ClientConfig.Qol q = qol();
        if (q == null || !q.hitDelayFix.enabled || !q.hitDelayFix.instantSwing) return vanilla;
        if (e != Minecraft.getMinecraft().thePlayer || animationEnd <= 0) return vanilla;
        return animationEnd; // swingProgressInt >= end / end, i.e. >= 1
    }

    // ---- Combat tracking ---------------------------------------------------------

    /** The server's hurt status (entity status 2) arrived for {@code e}. */
    public static void onHurtStatus(EntityLivingBase e) {
        KestrelClient c = KestrelMod.client();
        if (c == null || e.worldObj == null || !e.worldObj.isRemote) return;
        CombatTracker t = c.combat();
        if (t != null) t.onHurtStatus(e);
    }

    // ---- Hit Color ---------------------------------------------------------------

    /**
     * Colour multiplier for {@code RendererLivingEntity.setBrightness} while a
     * Hit Color tint is active on {@code e}, or 0 for none (vanilla). The alpha
     * byte encodes the tint strength: vanilla blends {@code rgb * (1 - a/255)}
     * over the texture, so strength {@code s} needs {@code a = (1 - s) * 255}.
     */
    public static int colorMultiplier(EntityLivingBase e) {
        KestrelClient c = KestrelMod.client();
        if (c == null) return 0;
        CombatTracker t = c.combat();
        if (t == null) return 0;
        float s = t.tintStrength(e.getEntityId());
        if (s <= 0.004f) return 0;
        if (s > 0.98f) s = 0.98f; // alpha must stay > 0 or vanilla ignores the multiplier
        int a = Math.round((1f - s) * 255f);
        return (a << 24) | (c.config().qol.hitColor.color & 0xFFFFFF);
    }

    // ---- No Blindness ------------------------------------------------------------

    /** Whether the fog code may treat {@code potion} as active (visual checks only). */
    public static boolean fogPotionActive(EntityLivingBase e, Potion potion) {
        boolean active = e.isPotionActive(potion);
        if (!active || potion != Potion.blindness) return active;
        ClientConfig.Qol q = qol();
        return q == null || !q.noBlindness.enabled;
    }

    // ---- Clear Water -------------------------------------------------------------

    private static ClientConfig.ClearWater water() {
        ClientConfig.Qol q = qol();
        return q != null && q.clearWater.enabled ? q.clearWater : null;
    }

    /** Underwater fog density, thinned by the clarity setting. */
    public static float waterFogDensity(float vanilla) {
        ClientConfig.ClearWater w = water();
        return w == null ? vanilla : vanilla * (1f - w.clarity);
    }

    /**
     * How far (0..0.6) the underwater fog colour is lifted toward a light
     * water blue, or 0 when Clear Water's brightness is off.
     */
    public static float waterFogLift() {
        ClientConfig.ClearWater w = water();
        return w == null ? 0f : Math.max(0f, Math.min(1f, w.brightness)) * 0.6f;
    }

    /** Alpha of the first-person underwater overlay texture. */
    public static float waterOverlayAlpha(float vanilla) {
        ClientConfig.ClearWater w = water();
        return w == null ? vanilla : vanilla * (1f - w.overlayTransparency);
    }

    // ---- Weather -----------------------------------------------------------------

    private static ClientConfig.Weather weather() {
        ClientConfig.Qol q = qol();
        return q != null && q.weather.enabled ? q.weather : null;
    }

    /** Rain/snow strength used for drawing rain and snow and for splash particles. */
    public static float weatherStrength(float vanilla) {
        ClientConfig.Weather w = weather();
        if (w == null) return vanilla;
        if (!w.rain && !w.snow) return 0f;
        return vanilla * w.intensity;
    }

    public static boolean weatherParticles() {
        ClientConfig.Weather w = weather();
        return w == null || w.particles;
    }

    public static boolean weatherSounds() {
        ClientConfig.Weather w = weather();
        return w == null || w.sounds;
    }

    public static boolean hideRain() {
        ClientConfig.Weather w = weather();
        return w != null && !w.rain;
    }

    public static boolean hideSnow() {
        ClientConfig.Weather w = weather();
        return w != null && !w.snow;
    }

    /** Clear-weather mode: the client world reports no rain or thunder. */
    public static boolean clearSky() {
        ClientConfig.Weather w = weather();
        return w != null && w.clearSky;
    }
}
