package com.kestrel.combat;

import com.kestrel.KestrelClient;
import com.kestrel.config.ClientConfig;
import com.kestrel.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Bridge Assist: a visual aid for placing blocks. While you hold a block and
 * look at a block face it draws where the block would go, highlights the face
 * you are aiming at, and (for side faces) marks that face's top edge with a
 * colour that turns bright when you stand close enough to the edge.
 *
 * <p>Drawing only. It reads the crosshair target the game already computed
 * ({@code objectMouseOver}) and asks {@link ItemBlock#canPlaceBlockOnSide} (a
 * read-only check) whether vanilla would accept the placement. It never moves
 * the player, turns the camera, selects a slot, places a block or sends packets.
 */
public final class BridgeAssist {
    private static final int INVALID_RGB = 0xE05A5A;
    private static final int GOOD_RGB = 0xFFFFFF;
    /** Faces are drawn this far outside the block so they don't z-fight. */
    private static final double EPS = 0.004;

    private final KestrelClient client;

    public BridgeAssist(KestrelClient client) {
        this.client = client;
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        ClientConfig.BridgeAssist cfg = client.config().qol.bridgeAssist;
        if (!cfg.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.thePlayer;
        World w = mc.theWorld;
        MovingObjectPosition hit = mc.objectMouseOver;
        if (p == null || w == null || hit == null || mc.gameSettings.hideGUI
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.sideHit == null) {
            return;
        }
        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;
        BlockPos pos = hit.getBlockPos();
        EnumFacing side = hit.sideHit;
        Block clicked = w.getBlockState(pos).getBlock();
        // Same rule as ItemBlock.onItemUse: a replaceable block (tall grass,
        // a snow layer...) is replaced in place; otherwise the new block goes
        // against the face.
        BlockPos target = clicked.isReplaceable(w, pos) ? pos : pos.offset(side);
        boolean valid = ((ItemBlock) held.getItem()).canPlaceBlockOnSide(w, pos, side, p, held);

        float pt = event.partialTicks;
        double cx = RenderUtil.camX(pt);
        double cy = RenderUtil.camY(pt);
        double cz = RenderUtil.camZ(pt);
        int rgb = (valid ? cfg.color : INVALID_RGB) & 0xFFFFFF;
        float alpha = cfg.opacity;
        float lineWidth = 1f + cfg.size;

        // Edge alignment for side faces (bridging): how close the player stands
        // to the targeted face's edge, using the interpolated position.
        float close = 0f;
        boolean sideFace = side.getAxis() != EnumFacing.Axis.Y;
        if (sideFace && cfg.alignmentGuide) {
            double px = RenderUtil.lerp(p.lastTickPosX, p.posX, pt);
            double pz = RenderUtil.lerp(p.lastTickPosZ, p.posZ, pt);
            close = BridgeGeometry.closeness(BridgeGeometry.edgeDistance(px, pz, pos.getX(), pos.getZ(), side.getIndex()));
        }
        boolean good = valid && close >= 1f;

        begin();
        try {
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            if (cfg.placementPreview) {
                double x0 = target.getX() - cx, y0 = target.getY() - cy, z0 = target.getZ() - cz;
                int fill = good ? mix(rgb, GOOD_RGB, 0.35f) : rgb;
                box(tess, wr, x0 + EPS, y0 + EPS, z0 + EPS, x0 + 1 - EPS, y0 + 1 - EPS, z0 + 1 - EPS, fill, alpha * 0.45f);
                GL11.glLineWidth(lineWidth);
                outline(tess, wr, x0 + EPS, y0 + EPS, z0 + EPS, x0 + 1 - EPS, y0 + 1 - EPS, z0 + 1 - EPS, fill, Math.min(1f, alpha * 2.2f));
            }
            face(tess, wr, pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz, side, rgb, Math.min(1f, alpha * 1.3f));
            if (sideFace && cfg.alignmentGuide) {
                int edge = mix(rgb, GOOD_RGB, close);
                GL11.glLineWidth(lineWidth + 1.5f * close);
                topEdge(tess, wr, pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz, side, edge, 0.5f + 0.5f * close);
            }
        } finally {
            end();
        }
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static void begin() {
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
    }

    private static void end() {
        GL11.glLineWidth(1f);
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }

    private static void color(WorldRenderer wr, double x, double y, double z, int rgb, float a) {
        wr.pos(x, y, z).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) (a * 255f)).endVertex();
    }

    private static void box(Tessellator tess, WorldRenderer wr, double x0, double y0, double z0,
                            double x1, double y1, double z1, int rgb, float a) {
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // bottom, top
        color(wr, x0, y0, z0, rgb, a); color(wr, x1, y0, z0, rgb, a); color(wr, x1, y0, z1, rgb, a); color(wr, x0, y0, z1, rgb, a);
        color(wr, x0, y1, z0, rgb, a); color(wr, x0, y1, z1, rgb, a); color(wr, x1, y1, z1, rgb, a); color(wr, x1, y1, z0, rgb, a);
        // north, south
        color(wr, x0, y0, z0, rgb, a); color(wr, x0, y1, z0, rgb, a); color(wr, x1, y1, z0, rgb, a); color(wr, x1, y0, z0, rgb, a);
        color(wr, x0, y0, z1, rgb, a); color(wr, x1, y0, z1, rgb, a); color(wr, x1, y1, z1, rgb, a); color(wr, x0, y1, z1, rgb, a);
        // west, east
        color(wr, x0, y0, z0, rgb, a); color(wr, x0, y0, z1, rgb, a); color(wr, x0, y1, z1, rgb, a); color(wr, x0, y1, z0, rgb, a);
        color(wr, x1, y0, z0, rgb, a); color(wr, x1, y1, z0, rgb, a); color(wr, x1, y1, z1, rgb, a); color(wr, x1, y0, z1, rgb, a);
        tess.draw();
    }

    private static void outline(Tessellator tess, WorldRenderer wr, double x0, double y0, double z0,
                                double x1, double y1, double z1, int rgb, float a) {
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        double[] xs = {x0, x1};
        double[] zs = {z0, z1};
        for (int i = 0; i < 2; i++) {
            double y = i == 0 ? y0 : y1;
            color(wr, x0, y, z0, rgb, a); color(wr, x1, y, z0, rgb, a);
            color(wr, x1, y, z0, rgb, a); color(wr, x1, y, z1, rgb, a);
            color(wr, x1, y, z1, rgb, a); color(wr, x0, y, z1, rgb, a);
            color(wr, x0, y, z1, rgb, a); color(wr, x0, y, z0, rgb, a);
        }
        for (double x : xs) {
            for (double z : zs) {
                color(wr, x, y0, z, rgb, a); color(wr, x, y1, z, rgb, a);
            }
        }
        tess.draw();
    }

    /** The targeted face of the block at (bx, by, bz) (camera-relative), filled. */
    private static void face(Tessellator tess, WorldRenderer wr, double bx, double by, double bz,
                             EnumFacing side, int rgb, float a) {
        double x0 = bx, y0 = by, z0 = bz, x1 = bx + 1, y1 = by + 1, z1 = bz + 1;
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        switch (side) {
            case DOWN:
                y0 -= EPS;
                color(wr, x0, y0, z0, rgb, a); color(wr, x1, y0, z0, rgb, a); color(wr, x1, y0, z1, rgb, a); color(wr, x0, y0, z1, rgb, a);
                break;
            case UP:
                y1 += EPS;
                color(wr, x0, y1, z0, rgb, a); color(wr, x0, y1, z1, rgb, a); color(wr, x1, y1, z1, rgb, a); color(wr, x1, y1, z0, rgb, a);
                break;
            case NORTH:
                z0 -= EPS;
                color(wr, x0, y0, z0, rgb, a); color(wr, x0, y1, z0, rgb, a); color(wr, x1, y1, z0, rgb, a); color(wr, x1, y0, z0, rgb, a);
                break;
            case SOUTH:
                z1 += EPS;
                color(wr, x0, y0, z1, rgb, a); color(wr, x1, y0, z1, rgb, a); color(wr, x1, y1, z1, rgb, a); color(wr, x0, y1, z1, rgb, a);
                break;
            case WEST:
                x0 -= EPS;
                color(wr, x0, y0, z0, rgb, a); color(wr, x0, y0, z1, rgb, a); color(wr, x0, y1, z1, rgb, a); color(wr, x0, y1, z0, rgb, a);
                break;
            case EAST:
            default:
                x1 += EPS;
                color(wr, x1, y0, z0, rgb, a); color(wr, x1, y1, z0, rgb, a); color(wr, x1, y1, z1, rgb, a); color(wr, x1, y0, z1, rgb, a);
                break;
        }
        tess.draw();
    }

    /** A line along the top edge of a side face: the edge you bridge from. */
    private static void topEdge(Tessellator tess, WorldRenderer wr, double bx, double by, double bz,
                                EnumFacing side, int rgb, float a) {
        double y = by + 1 + EPS;
        double ax, az, cx, cz;
        switch (side) {
            case NORTH: ax = bx; az = bz - EPS; cx = bx + 1; cz = bz - EPS; break;
            case SOUTH: ax = bx; az = bz + 1 + EPS; cx = bx + 1; cz = bz + 1 + EPS; break;
            case WEST: ax = bx - EPS; az = bz; cx = bx - EPS; cz = bz + 1; break;
            case EAST: ax = bx + 1 + EPS; az = bz; cx = bx + 1 + EPS; cz = bz + 1; break;
            default: return;
        }
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        color(wr, ax, y, az, rgb, a);
        color(wr, cx, y, cz, rgb, a);
        tess.draw();
    }
}
