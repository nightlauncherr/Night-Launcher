package com.kestrel.openmap;

import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

/** Draws the Open Map opponent as a player wearing its armor and holding its current item. */
public class RenderPvpBot extends RendererLivingEntity<EntityPvpBot> {
    public RenderPvpBot(RenderManager manager) {
        super(manager, new ModelPlayer(0.0F, false), 0.5F);
        addLayer(new LayerBipedArmor(this));
        addLayer(new LayerHeldItem(this));
    }

    @Override
    public void doRender(EntityPvpBot entity, double x, double y, double z, float entityYaw, float partialTicks) {
        ModelPlayer model = (ModelPlayer) getMainModel();
        model.heldItemRight = entity.getHeldItem() == null ? 0 : 1;
        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityPvpBot entity) {
        return DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    @Override
    protected void preRenderCallback(EntityPvpBot entity, float partialTicks) {
        GlStateManager.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
