package com.kestrel.animation;

import com.kestrel.KestrelClient;
import com.kestrel.KestrelMod;
import com.kestrel.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Kestrel's first-person held-item animation (called from
 * {@code MixinItemRenderer}, render thread only).
 *
 * <p>It only changes where the held sword or tool is drawn in first person.
 * Attacks are started, timed and sent by vanilla code that is not touched; the
 * animation merely starts when vanilla's own arm swing starts. Reach, rotation
 * and hit registration are unaffected. Eating, drinking, bows and maps keep
 * vanilla's animations (with an eased item switch).
 */
public final class FirstPersonAnimator {
    /** Kestrel accent (trail colour when not taken from the item). */
    private static final int ACCENT = 0xFFE8833A;
    /** Trail length in normalised swing time. */
    private static final float TRAIL_SPAN = 0.22f;
    private static final int TRAIL_SAMPLES = 28;

    private static final SwingState STATE = new SwingState();
    private static final float[] POSE = new float[SwingStyle.CHANNELS];
    private static final float[] LAST_POSE = new float[SwingStyle.CHANNELS];
    private static final float[] SAMPLE_POSE = new float[SwingStyle.CHANNELS];
    private static final float[] SWAY = new float[2];
    private static final float[] NO_SWAY = new float[2];
    private static final float[] P = new float[3];
    private static final Mat4 SAMPLE = new Mat4();
    private static final Mat4 ITEM = itemChain();
    private static final Ops GL = new Ops() {
        @Override
        public void translate(float x, float y, float z) {
            GlStateManager.translate(x, y, z);
        }

        @Override
        public void rotate(float degrees, float x, float y, float z) {
            GlStateManager.rotate(degrees, x, y, z);
        }

        @Override
        public void scale(float x, float y, float z) {
            GlStateManager.scale(x, y, z);
        }
    };
    /**
     * Diagnostic slow motion for checking the animation frame by frame
     * ({@code -Dkestrel.animTimeScale=0.05}); 1 (real time) unless set.
     * Only the animation clock is scaled, never game or input timing.
     */
    private static final double TIME_SCALE = timeScale();
    private static final Map<Item, Integer> COLORS = new IdentityHashMap<Item, Integer>();

    private static int lastSwingInt;
    private static boolean lastSwinging;
    private static boolean lastAttackDown;

    private FirstPersonAnimator() {
    }

    /** Which style animates this item now, or null for vanilla. */
    static SwingStyle styleFor(ItemStack stack, ClientConfig.Animations a) {
        if (stack == null) return null;
        Item item = stack.getItem();
        if (item instanceof ItemSword) {
            return a.swordEnabled ? nonVanilla(SwingStyle.byId(a.swordStyle, SwingStyle.KESTREL)) : null;
        }
        if (item instanceof ItemTool || item instanceof ItemHoe || item instanceof ItemFishingRod) {
            return a.toolsEnabled ? nonVanilla(SwingStyle.byId(a.toolStyle, SwingStyle.SMOOTH)) : null;
        }
        return null;
    }

    private static double timeScale() {
        try {
            double v = Double.parseDouble(System.getProperty("kestrel.animTimeScale", "1"));
            return v >= 0.01 && v <= 1.0 ? v : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private static SwingStyle nonVanilla(SwingStyle s) {
        return s == SwingStyle.VANILLA ? null : s;
    }

    private static ClientConfig.Animations cfg() {
        KestrelClient c = KestrelMod.client();
        return c == null ? null : c.config().animations;
    }

    /** True when Kestrel replaces vanilla's swing transform for this item (doItemUsedTransformations). */
    public static boolean handlesSwing(ItemStack stack) {
        ClientConfig.Animations a = cfg();
        return a != null && styleFor(stack, a) != null;
    }

    /** True when Kestrel already applied the block pose (doBlockTransformations is then skipped). */
    public static boolean handlesBlock(ItemStack stack) {
        ClientConfig.Animations a = cfg();
        return a != null && stack != null && stack.getItem() instanceof ItemSword && styleFor(stack, a) != null;
    }

    /**
     * Replacement for vanilla's {@code transformFirstPersonItem(equip, swing)}.
     *
     * @return true if the item was placed here and vanilla's code must be skipped
     */
    public static boolean transform(ItemStack stack, float equip, float vanillaSwing) {
        ClientConfig.Animations a = cfg();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (a == null || player == null) return false;
        long now = TIME_SCALE == 1.0 ? System.nanoTime() : (long) (System.nanoTime() * TIME_SCALE);
        float eq = a.smoothSwitch ? SwingStyle.easeEquip(equip) : equip;
        SwingStyle style = styleFor(stack, a);
        boolean inUse = player.getItemInUseCount() > 0;
        boolean blocking = inUse && stack != null && stack.getItemUseAction() == EnumAction.BLOCK;

        detectSwing(mc, player, now, blocking && a.swingWhileBlocking);

        if (style == null || (inUse && !blocking)) {
            // Not animated (or eating/drinking/bow): vanilla's transform, only the item switch eased.
            if (!a.smoothSwitch) return false;
            SwingStyle.vanillaTransform(GL, eq, vanillaSwing);
            return true;
        }
        float duration = SwingState.BASE_DURATION / a.speed;
        float t = STATE.progress(now, duration);
        if (blocking && !a.swingWhileBlocking) t = 1f;
        float block = handlesBlock(stack) ? STATE.updateBlock(now, blocking, a.smoothBlock) : 0f;
        style.pose(t, a.intensity, POSE);
        STATE.blend(now, POSE);
        System.arraycopy(POSE, 0, LAST_POSE, 0, POSE.length);
        if (a.idleSway && t >= 1f && block < 0.01f) {
            SwingState.sway(now, a.intensity, SWAY);
        } else {
            SWAY[0] = 0f;
            SWAY[1] = 0f;
        }
        if (style == SwingStyle.KESTREL && a.trail && block < 0.5f) {
            float tv = STATE.secondsSinceStart(now) / duration;
            drawTrail(stack, style, eq, tv, a);
        }
        style.apply(GL, eq, t, POSE, block, SWAY, a.intensity);
        return true;
    }

    /** Start the animation when vanilla's own arm swing starts (or on a click while blocking, if enabled). */
    private static void detectSwing(Minecraft mc, EntityPlayerSP player, long now, boolean visualBlockSwing) {
        int spi = player.swingProgressInt;
        boolean sw = player.isSwingInProgress;
        if (sw && (!lastSwinging || spi < lastSwingInt)) {
            STATE.startSwing(now, LAST_POSE);
        }
        lastSwinging = sw;
        lastSwingInt = spi;
        boolean attack = mc.gameSettings.keyBindAttack.isKeyDown();
        if (visualBlockSwing && attack && !lastAttackDown) {
            STATE.startSwing(now, LAST_POSE);
        }
        lastAttackDown = attack;
    }

    // ---- slash trail ------------------------------------------------------------

    /**
     * Where the blade's tip and inner edge are within the item's own frame:
     * Minecraft's handheld item model (16x16 sprite, blade from bottom-left
     * handle to top-right tip) placed the way RenderItem does in first person.
     */
    private static Mat4 itemChain() {
        Mat4 m = new Mat4();
        m.scale(2f, 2f, 2f);                    // RenderItem.preTransform (flat items)
        m.translate(0f, 4f / 16f, 2f / 16f);     // handheld.json firstperson translation
        m.rotate(-135f, 0f, 1f, 0f);             // rotation [0, -135, 25]
        m.rotate(25f, 0f, 0f, 1f);
        m.scale(1.7f, 1.7f, 1.7f);
        m.scale(0.5f, 0.5f, 0.5f);               // RenderItem.renderItem
        m.translate(-0.5f, -0.5f, -0.5f);
        return m;
    }

    private static void drawTrail(ItemStack stack, SwingStyle style, float equip, float tv, ClientConfig.Animations a) {
        float from = Math.max(SwingStyle.TRAIL_START, tv - TRAIL_SPAN);
        float to = Math.min(tv, SwingStyle.TRAIL_END);
        if (to <= from) return;
        float fade = tv > SwingStyle.TRAIL_END ? 1f - (tv - SwingStyle.TRAIL_END) / TRAIL_SPAN : 1f;
        if (fade <= 0.01f) return;
        int argb = a.trailItemColor ? itemColor(stack) : ACCENT;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

        GlStateManager.disableLighting();
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        // Soft body of the sweep (inner edge to tip) and a bright leading edge near the tip.
        for (int layer = 0; layer < 2; layer++) {
            float inner = layer == 0 ? 0.42f : 0.86f;
            float aInner = layer == 0 ? 0.0f : 0.55f;
            float aTip = layer == 0 ? 0.42f : 0.95f;
            wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= TRAIL_SAMPLES; i++) {
                float s = from + (to - from) * i / TRAIL_SAMPLES;
                float age = (tv - s) / TRAIL_SPAN; // 0 = newest
                float w = (1f - age);
                w = w <= 0f ? 0f : w * w * fade;
                SAMPLE.identity();
                style.pose(s, a.intensity, SAMPLE_POSE);
                style.apply(SAMPLE, equip, s, SAMPLE_POSE, 0f, NO_SWAY, a.intensity);
                SAMPLE.mul(ITEM);
                float edge = layer == 0 ? 0.94f : 0.97f;
                SAMPLE.transformPoint(inner, inner, 0.5f, P);
                wr.pos(P[0], P[1], P[2]).color(r, g, b, aInner * w).endVertex();
                SAMPLE.transformPoint(edge, edge, 0.5f, P);
                float wr2 = layer == 1 ? 1f : 0f; // leading edge drifts towards white
                wr.pos(P[0], P[1], P[2]).color(r + (1f - r) * 0.6f * wr2, g + (1f - g) * 0.6f * wr2,
                        b + (1f - b) * 0.6f * wr2, aTip * w).endVertex();
            }
            tess.draw();
        }

        GlStateManager.shadeModel(shade);
        GlStateManager.depthMask(true);
        if (cull) GlStateManager.enableCull();
        if (alpha) GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (!blend) GlStateManager.disableBlend();
        if (lightmap) {
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
        if (texture) GlStateManager.enableTexture2D();
        if (lighting) GlStateManager.enableLighting();
        GlStateManager.resetColor();
    }

    /** A bright, saturated colour from the item's texture (cached per item). */
    static int itemColor(ItemStack stack) {
        Item item = stack.getItem();
        Integer c = COLORS.get(item);
        if (c != null) return c;
        int color = 0xFF9FD8FF;
        try {
            TextureAtlasSprite sprite = Minecraft.getMinecraft().getRenderItem().getItemModelMesher().getParticleIcon(item);
            if (sprite != null && sprite.getFrameCount() > 0) {
                color = TrailColor.fromPixels(sprite.getFrameTextureData(0)[0]);
            }
        } catch (RuntimeException e) {
            KestrelMod.LOG.debug("[Kestrel] no trail colour for {}: {}", item, e.toString());
        }
        COLORS.put(item, color);
        return color;
    }

    /** Play one swing of the current style without attacking (settings preview; purely visual). */
    public static void previewSwing() {
        long now = TIME_SCALE == 1.0 ? System.nanoTime() : (long) (System.nanoTime() * TIME_SCALE);
        STATE.startSwing(now, LAST_POSE);
    }

    /** Forget state (world change). */
    public static void reset() {
        STATE.reset();
        lastSwinging = false;
        lastSwingInt = 0;
    }
}
