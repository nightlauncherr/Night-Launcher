package com.kestrel.mixin;

import com.kestrel.combat.QolHooks;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fog hooks for No Blindness and Clear Water (all verified against the 1.8.9
 * Forge bytecode of {@code updateFogColor(float)} and {@code setupFog(int, float)}).
 *
 * <ul>
 * <li>No Blindness: both methods ask {@code EntityLivingBase.isPotionActive}
 * for blindness to darken the fog colour and pull the fog in to 5 blocks.
 * Only those two visual checks are answered "not active"; the potion effect,
 * its duration and everything that depends on it (such as being unable to
 * sprint) stay as the server sent them. Other potions pass through.</li>
 * <li>Clear Water: while the camera is in water, every
 * {@code GlStateManager.setFogDensity} in {@code setupFog} (vanilla's 0.1 or
 * 0.01 with Water Breathing, minus Respiration, or Forge's event value) is
 * thinned by the clarity setting, and the water fog colour is lifted at the
 * end of {@code updateFogColor} before the clear colour is set. Blocks,
 * collision and fluid physics are untouched.</li>
 * </ul>
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererQol {
    // Light water blue the fog colour is lifted toward by Clear Water's brightness.
    private static final float KESTREL_WATER_R = 0.40f;
    private static final float KESTREL_WATER_G = 0.62f;
    private static final float KESTREL_WATER_B = 0.90f;

    @Shadow
    private Minecraft mc;
    @Shadow
    private float fogColorRed;
    @Shadow
    private float fogColorGreen;
    @Shadow
    private float fogColorBlue;

    /** Whether the camera is in water for the setupFog call in progress (render thread). */
    private boolean kestrel$fogInWater;

    private boolean kestrel$viewInWater(float partialTicks) {
        Entity view = mc.getRenderViewEntity();
        if (view == null || mc.theWorld == null) return false;
        return ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, view, partialTicks).getMaterial() == Material.water;
    }

    // ---- No Blindness ----------------------------------------------------------

    @Redirect(method = "updateFogColor", require = 3, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean kestrel$fogColorPotion(EntityLivingBase e, Potion potion) {
        return QolHooks.fogPotionActive(e, potion);
    }

    @Redirect(method = "setupFog", require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean kestrel$fogPotion(EntityLivingBase e, Potion potion) {
        return QolHooks.fogPotionActive(e, potion);
    }

    // ---- Clear Water -----------------------------------------------------------

    @Inject(method = "setupFog", at = @At("HEAD"), require = 1)
    private void kestrel$fogStart(int startCoords, float partialTicks, CallbackInfo ci) {
        kestrel$fogInWater = QolHooks.waterFogDensity(1f) != 1f && kestrel$viewInWater(partialTicks);
    }

    @Redirect(method = "setupFog", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;setFogDensity(F)V"))
    private void kestrel$fogDensity(float density) {
        GlStateManager.setFogDensity(kestrel$fogInWater ? QolHooks.waterFogDensity(density) : density);
    }

    @Inject(method = "updateFogColor", at = @At("TAIL"), require = 1)
    private void kestrel$waterFogColor(float partialTicks, CallbackInfo ci) {
        float lift = QolHooks.waterFogLift();
        if (lift <= 0f || !kestrel$viewInWater(partialTicks)) return;
        fogColorRed += (KESTREL_WATER_R - fogColorRed) * lift;
        fogColorGreen += (KESTREL_WATER_G - fogColorGreen) * lift;
        fogColorBlue += (KESTREL_WATER_B - fogColorBlue) * lift;
        GlStateManager.clearColor(fogColorRed, fogColorGreen, fogColorBlue, 0f);
    }
}
