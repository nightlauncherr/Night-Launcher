package com.kestrel.mixin;

import com.kestrel.combat.QolHooks;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather Customizer's clear-weather mode: the client's own world copy
 * ({@code isRemote}) reports no rain or thunder, so the sky, fog, light,
 * rain, snow and rain sounds look and sound like clear weather. The server's
 * world (including the singleplayer integrated server, which is not remote)
 * and the weather the server sends are never changed, and world time is not
 * touched.
 */
@Mixin(World.class)
public abstract class MixinWorldWeather {
    @Shadow
    @Final
    public boolean isRemote;

    @Inject(method = "getRainStrength", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$rain(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (isRemote && QolHooks.clearSky()) cir.setReturnValue(0f);
    }

    @Inject(method = "getThunderStrength", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$thunder(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (isRemote && QolHooks.clearSky()) cir.setReturnValue(0f);
    }
}
