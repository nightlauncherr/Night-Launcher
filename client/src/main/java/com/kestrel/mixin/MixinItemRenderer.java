package com.kestrel.mixin;

import com.kestrel.animation.FirstPersonAnimator;
import com.kestrel.combat.QolHooks;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person held-item animation hooks (methods verified against the 1.8.9
 * bytecode of {@code renderItemInFirstPerson}: the normal path calls
 * {@code doItemUsedTransformations(swing)} then {@code transformFirstPersonItem(equip, swing)};
 * blocking calls {@code transformFirstPersonItem(equip, 0)} then {@code doBlockTransformations()}).
 *
 * <p>Only these three transform helpers are replaced, and only while a Kestrel
 * animation applies to the held item; they position the item in the hand
 * pass and nothing else. Attack input, timing, reach and rotation live
 * elsewhere and are untouched.
 *
 * <p>Clear Water: {@code renderWaterOverlayTexture} sets the underwater
 * overlay colour with {@code GlStateManager.color(b, b, b, 0.5f)} (its first
 * {@code color} call; the second resets to white). Only that alpha is scaled by
 * the overlay transparency setting.
 */
@Mixin(ItemRenderer.class)
public abstract class MixinItemRenderer {
    @Shadow
    private ItemStack itemToRender;

    @Inject(method = "doItemUsedTransformations", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$swingOffset(float swingProgress, CallbackInfo ci) {
        if (FirstPersonAnimator.handlesSwing(this.itemToRender)) {
            ci.cancel(); // folded into Kestrel's pose (transformFirstPersonItem below)
        }
    }

    @Inject(method = "transformFirstPersonItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$transform(float equipProgress, float swingProgress, CallbackInfo ci) {
        if (FirstPersonAnimator.transform(this.itemToRender, equipProgress, swingProgress)) {
            ci.cancel();
        }
    }

    @Inject(method = "doBlockTransformations", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$block(CallbackInfo ci) {
        if (FirstPersonAnimator.handlesBlock(this.itemToRender)) {
            ci.cancel(); // the (eased) block pose was already applied
        }
    }

    @Redirect(method = "renderWaterOverlayTexture", require = 1, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V"))
    private void kestrel$waterOverlay(float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, QolHooks.waterOverlayAlpha(a));
    }
}
