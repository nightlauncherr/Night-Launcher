package com.kestrel.mixin;

import com.kestrel.combat.QolHooks;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hit Color through vanilla's own entity tint (verified against the 1.8.9
 * bytecode of {@code setBrightness(T, float, boolean)}): the tint comes from
 * {@code getColorMultiplier} (local 5) unless the hurt flag
 * ({@code hurtTime > 0 || deathTime > 0}) selects the fixed red overlay.
 *
 * <p>While a Hit Color tint is active on an entity, {@code getColorMultiplier}
 * returns the configured colour and the hurt-flag reads see 0 so the custom
 * colour shows instead of vanilla's red. Armour layers call
 * {@code setBrightness} again, so armour is tinted the same way. The skin
 * texture, model, hitbox and the entity's real {@code hurtTime} are untouched.
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntityQol {
    @Inject(method = "getColorMultiplier", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$hitColor(EntityLivingBase e, float lightBrightness, float partialTicks,
                                  CallbackInfoReturnable<Integer> cir) {
        int c = QolHooks.colorMultiplier(e);
        if (c != 0) cir.setReturnValue(c);
    }

    // The hurt flag: setBrightness reads hurtTime and deathTime once each to
    // choose vanilla's red overlay. While a tint is active these reads return
    // 0 inside setBrightness only, so the configured colour is used instead.

    @Redirect(method = "setBrightness", require = 1, at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/EntityLivingBase;hurtTime:I", opcode = Opcodes.GETFIELD))
    private int kestrel$hurtTime(EntityLivingBase e) {
        return QolHooks.colorMultiplier(e) != 0 ? 0 : e.hurtTime;
    }

    @Redirect(method = "setBrightness", require = 1, at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/EntityLivingBase;deathTime:I", opcode = Opcodes.GETFIELD))
    private int kestrel$deathTime(EntityLivingBase e) {
        return QolHooks.colorMultiplier(e) != 0 ? 0 : e.deathTime;
    }
}
