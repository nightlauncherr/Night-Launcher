package com.kestrel.mixin;

import com.kestrel.cosmetics.CapeRenderer;
import com.kestrel.skin.SkinHooks;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Local skin changer: the local player's entity uses the skin chosen in
 * Kestrel (third person, inventory, first-person arm, all via these
 * methods). Every other player, and the local player with no custom skin
 * active, is unchanged.
 *
 * <p>Capes: while the local player has a Kestrel cape equipped and loaded,
 * {@code getLocationCape} reports no official cape for them, so vanilla's
 * LayerCape stays out of the way and Kestrel's CapeRenderer draws the
 * equipped cape (see {@link CapeRenderer#hidesOfficialCape}). Every other
 * player's official cape is unchanged.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer {
    @Inject(method = "getLocationSkin()Lnet/minecraft/util/ResourceLocation;", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$localSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation r = SkinHooks.skin(((AbstractClientPlayer) (Object) this).getGameProfile());
        if (r != null) cir.setReturnValue(r);
    }

    @Inject(method = "getSkinType", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$localSkinModel(CallbackInfoReturnable<String> cir) {
        String m = SkinHooks.model(((AbstractClientPlayer) (Object) this).getGameProfile());
        if (m != null) cir.setReturnValue(m);
    }

    @Inject(method = "hasSkin", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$hasLocalSkin(CallbackInfoReturnable<Boolean> cir) {
        if (SkinHooks.skin(((AbstractClientPlayer) (Object) this).getGameProfile()) != null) cir.setReturnValue(true);
    }

    @Inject(method = "getLocationCape", at = @At("HEAD"), cancellable = true, require = 1)
    private void kestrel$equippedCapeWins(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CapeRenderer.hidesOfficialCape((AbstractClientPlayer) (Object) this)) cir.setReturnValue(null);
    }
}
