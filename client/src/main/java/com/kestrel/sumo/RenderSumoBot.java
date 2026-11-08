package com.kestrel.sumo;

import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

/** Draws the Sumo opponent as a player (classic model, the default Steve skin). */
public class RenderSumoBot extends RendererLivingEntity<EntitySumoBot> {
    public RenderSumoBot(RenderManager manager) {
        super(manager, new ModelPlayer(0.0F, false), 0.5F);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntitySumoBot entity) {
        return DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    @Override
    protected void preRenderCallback(EntitySumoBot entity, float partialTicks) {
        // Same scale RenderPlayer uses, so it is exactly player-sized.
        GlStateManager.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
