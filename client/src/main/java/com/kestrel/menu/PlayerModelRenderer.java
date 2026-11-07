package com.kestrel.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Draws a posed player model (classic or slim, base layer plus every second
 * layer: hat, jacket, sleeves, trousers) with a skin texture in GUI space,
 * without needing a player entity, optionally with a cape (the equipped Night
 * cape, so the menus show what you wear in game). Used by the main menu and
 * the Skin screen.
 *
 * <p>Restores the GL state it changes (depth, lighting, blend, cull, rescale
 * normal, lightmap unit, colour) so the GUI drawn afterwards is unaffected.
 */
public final class PlayerModelRenderer {
    /**
     * Limb angles in radians; body yaw in degrees (0 = facing the viewer);
     * {@code viewPitch} in degrees tilts the whole model about its middle
     * (positive = top towards the viewer, as if seen from above).
     */
    public static final class Pose {
        public float yaw;
        public float viewPitch;
        public float lean;
        public float bob;
        public float headYaw;
        public float headPitch;
        public float rightArmX, leftArmX, rightArmZ, leftArmZ;
        public float rightLegX, leftLegX;

        public void reset() {
            yaw = viewPitch = lean = bob = headYaw = headPitch = 0f;
            rightArmX = leftArmX = rightArmZ = leftArmZ = 0f;
            rightLegX = leftLegX = 0f;
        }
    }

    private final ModelPlayer classic = new ModelPlayer(0.0F, false);
    private final ModelPlayer slim = new ModelPlayer(0.0F, true);

    /**
     * Draw the player with its feet at (cx, feetY), {@code scale} GUI pixels
     * per block (the model is two blocks tall).
     */
    public void draw(Minecraft mc, float cx, float feetY, float scale, ResourceLocation tex, boolean slimArms, Pose pose) {
        draw(mc, cx, feetY, scale, tex, slimArms, pose, null);
    }

    /**
     * As {@link #draw(Minecraft, float, float, float, ResourceLocation, boolean, Pose)}, plus
     * {@code cape} (a cape texture, or null for none) hanging from the shoulders the way
     * vanilla's LayerCape draws it, swung back a little while the body leans.
     */
    public void draw(Minecraft mc, float cx, float feetY, float scale, ResourceLocation tex, boolean slimArms, Pose pose,
                     ResourceLocation cape) {
        ModelPlayer m = slimArms ? slim : classic;
        pose(m, pose);

        GlStateManager.pushMatrix();
        // Fresh depth so the model never fights whatever was drawn before it.
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableColorMaterial();
        GlStateManager.translate(cx, feetY - pose.bob * scale, 150.0F);
        // Vanilla's inventory-preview transform, so the item lighting comes from the usual direction.
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        // Viewing angle: tilt about the model's middle (one block up from the feet; up is -Y
        // here), after the lighting is placed, so the light stays where it is relative to the viewer.
        if (pose.viewPitch != 0.0F) {
            GlStateManager.translate(0.0F, -1.0F, 0.0F);
            GlStateManager.rotate(-pose.viewPitch, 1.0F, 0.0F, 0.0F); // positive: seen from above
            GlStateManager.translate(0.0F, 1.0F, 0.0F);
        }
        // Face the viewer at yaw 0.
        GlStateManager.rotate(180.0F + pose.yaw, 0.0F, 1.0F, 0.0F);
        // Lean forward from the feet.
        GlStateManager.rotate((float) Math.toDegrees(pose.lean), 1.0F, 0.0F, 0.0F);
        GlStateManager.translate(0.0F, -1.5F, 0.0F);
        GlStateManager.enableRescaleNormal();
        GlStateManager.disableCull();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(tex);
        float s = 0.0625F;
        m.bipedHead.render(s);
        m.bipedBody.render(s);
        m.bipedRightArm.render(s);
        m.bipedLeftArm.render(s);
        m.bipedRightLeg.render(s);
        m.bipedLeftLeg.render(s);
        // Second layer last, so its transparent pixels blend over the base.
        m.bipedHeadwear.render(s);
        m.bipedBodyWear.render(s);
        m.bipedRightArmwear.render(s);
        m.bipedLeftArmwear.render(s);
        m.bipedRightLegwear.render(s);
        m.bipedLeftLegwear.render(s);

        if (cape != null) {
            // LayerCape (1.8.9): from the body origin, 2 px behind the back, tilted out, flipped to face back.
            mc.getTextureManager().bindTexture(cape);
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, 0.0F, 0.125F);
            float swing = Math.min(40.0F, Math.abs((float) Math.toDegrees(pose.lean)) * 3.0F);
            GlStateManager.rotate(6.0F + swing, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
            com.kestrel.cosmetics.NightCapeModel.render(s); // not m.renderCape: cape mods cancel that
            GlStateManager.popMatrix();
        }

        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableDepth();
        GlStateManager.disableColorMaterial();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void pose(ModelPlayer m, Pose p) {
        zero(m.bipedHead);
        zero(m.bipedBody);
        zero(m.bipedRightArm);
        zero(m.bipedLeftArm);
        zero(m.bipedRightLeg);
        zero(m.bipedLeftLeg);
        m.bipedHead.rotateAngleY = p.headYaw;
        m.bipedHead.rotateAngleX = p.headPitch;
        m.bipedRightArm.rotateAngleX = p.rightArmX;
        m.bipedLeftArm.rotateAngleX = p.leftArmX;
        m.bipedRightArm.rotateAngleZ = p.rightArmZ;
        m.bipedLeftArm.rotateAngleZ = p.leftArmZ;
        m.bipedRightLeg.rotateAngleX = p.rightLegX;
        m.bipedLeftLeg.rotateAngleX = p.leftLegX;
        // The second layer follows the base layer (as ModelPlayer.setRotationAngles does).
        ModelBase.copyModelAngles(m.bipedHead, m.bipedHeadwear);
        ModelBase.copyModelAngles(m.bipedBody, m.bipedBodyWear);
        ModelBase.copyModelAngles(m.bipedRightArm, m.bipedRightArmwear);
        ModelBase.copyModelAngles(m.bipedLeftArm, m.bipedLeftArmwear);
        ModelBase.copyModelAngles(m.bipedRightLeg, m.bipedRightLegwear);
        ModelBase.copyModelAngles(m.bipedLeftLeg, m.bipedLeftLegwear);
    }

    private static void zero(ModelRenderer r) {
        r.rotateAngleX = 0f;
        r.rotateAngleY = 0f;
        r.rotateAngleZ = 0f;
    }
}
