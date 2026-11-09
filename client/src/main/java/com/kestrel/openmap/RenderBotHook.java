package com.kestrel.openmap;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/** Draws the opponent's hook and fishing line like vanilla RenderFish does for a player in third person. */
public class RenderBotHook extends Render<BotFishHook> {
    private static final ResourceLocation PARTICLES = new ResourceLocation("textures/particle/particles.png");

    public RenderBotHook(RenderManager manager) {
        super(manager);
    }

    @Override
    public void doRender(BotFishHook entity, double x, double y, double z, float entityYaw, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.enableRescaleNormal();
        GlStateManager.scale(0.5F, 0.5F, 0.5F);
        bindEntityTexture(entity);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.rotate(180.0F - renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        wr.begin(7, DefaultVertexFormats.POSITION_TEX_NORMAL);
        wr.pos(-0.5D, -0.5D, 0.0D).tex(0.0625D, 0.1875D).normal(0.0F, 1.0F, 0.0F).endVertex();
        wr.pos(0.5D, -0.5D, 0.0D).tex(0.125D, 0.1875D).normal(0.0F, 1.0F, 0.0F).endVertex();
        wr.pos(0.5D, 0.5D, 0.0D).tex(0.125D, 0.125D).normal(0.0F, 1.0F, 0.0F).endVertex();
        wr.pos(-0.5D, 0.5D, 0.0D).tex(0.0625D, 0.125D).normal(0.0F, 1.0F, 0.0F).endVertex();
        tessellator.draw();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
        EntityPvpBot a = entity.angler();
        if (a != null) {
            float yaw = (a.prevRenderYawOffset + (a.renderYawOffset - a.prevRenderYawOffset) * partialTicks) * (float) Math.PI / 180.0F;
            double sin = MathHelper.sin(yaw);
            double cos = MathHelper.cos(yaw);
            double hx = a.prevPosX + (a.posX - a.prevPosX) * partialTicks - cos * 0.35D - sin * 0.8D;
            double hy = a.prevPosY + a.getEyeHeight() + (a.posY - a.prevPosY) * partialTicks - 0.45D;
            double hz = a.prevPosZ + (a.posZ - a.prevPosZ) * partialTicks - sin * 0.35D + cos * 0.8D;
            double ex = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
            double ey = entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks + 0.25D;
            double ez = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
            double dx = (float) (hx - ex);
            double dy = (float) (hy - ey);
            double dz = (float) (hz - ez);
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            wr.begin(3, DefaultVertexFormats.POSITION_COLOR);
            for (int l = 0; l <= 16; ++l) {
                float f = l / 16.0F;
                wr.pos(x + dx * f, y + dy * (f * f + f) * 0.5D + 0.25D, z + dz * f).color(0, 0, 0, 255).endVertex();
            }
            tessellator.draw();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
        }
        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }

    @Override
    protected ResourceLocation getEntityTexture(BotFishHook entity) {
        return PARTICLES;
    }
}
