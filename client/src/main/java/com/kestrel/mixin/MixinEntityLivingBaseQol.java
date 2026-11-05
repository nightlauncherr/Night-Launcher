package com.kestrel.mixin;

import com.kestrel.combat.QolHooks;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat hooks on {@link EntityLivingBase} (verified against the 1.8.9
 * bytecode).
 *
 * <ul>
 * <li>{@code handleStatusUpdate(byte)}: status 2 is the server's "this entity
 * was hurt" broadcast. It is observed at HEAD and passed on unchanged, so Hit
 * Color and Opponent Health Prediction only react to hits the server confirmed.</li>
 * <li>{@code swingItem()}: the only {@code iconst_2} is the divisor in
 * {@code swingProgressInt >= getArmSwingAnimationEnd() / 2}, the rule that
 * decides whether a click restarts the swing animation. Hit Delay Fix changes
 * it for the local player only (see {@link QolHooks#swingRestartDivisor}).</li>
 * </ul>
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBaseQol {
    @Shadow
    private int getArmSwingAnimationEnd() {
        throw new AssertionError();
    }

    @Inject(method = "handleStatusUpdate", at = @At("HEAD"), require = 1)
    private void kestrel$statusUpdate(byte id, CallbackInfo ci) {
        if (id == 2) {
            QolHooks.onHurtStatus((EntityLivingBase) (Object) this);
        }
    }

    @ModifyConstant(method = "swingItem", constant = @Constant(intValue = 2), require = 1)
    private int kestrel$swingRestart(int vanilla) {
        return QolHooks.swingRestartDivisor((EntityLivingBase) (Object) this, vanilla, getArmSwingAnimationEnd());
    }
}
