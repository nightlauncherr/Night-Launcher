package com.kestrel.mixin;

import com.kestrel.openmap.OpenMapCull;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.AxisAlignedBB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Open Map PvP: chunks far outside the play area fail the visibility test in
 * {@code setupTerrain} (both frustum checks: the start set and the flood
 * fill), so they are neither built nor drawn. Outside Open Map the vanilla
 * test runs unchanged.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalOpenMap {
    @Redirect(method = "setupTerrain", require = 2,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/culling/ICamera;isBoundingBoxInFrustum(Lnet/minecraft/util/AxisAlignedBB;)Z"))
    private boolean kestrel$openMapCull(ICamera camera, AxisAlignedBB box) {
        return OpenMapCull.inFrustum(camera, box);
    }
}
