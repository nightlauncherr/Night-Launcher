package com.kestrel.openmap;

import com.kestrel.KestrelMod;
import com.kestrel.menu.KestrelMainMenu;
import com.kestrel.sumo.SumoClient;
import com.kestrel.util.RenderUtil;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * Client side of Open Map PvP: the practice menu key (M, shared with Sumo),
 * opening the menu when the session asks (entering the world, /stopopenmap),
 * the lobby banner, and the launcher's OPEN MAP PVP button
 * ({@code -Dkestrel.openMap=true}), which opens the world from the main menu.
 */
public final class OpenMapClient {
    static final String OPEN_PROPERTY = "kestrel.openMap";
    private static final int AUTO_OPEN_DELAY = 10;

    private boolean autoOpenPending = Boolean.getBoolean(OPEN_PROPERTY);
    private int menuTicks;

    OpenMapClient() {
        if (autoOpenPending) KestrelMod.LOG.info("[Kestrel] launched with the OPEN MAP PVP button: the world opens from the main menu");
    }

    static KeyBinding key() {
        return SumoClient.practiceMenuKey();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (autoOpenPending) autoOpen(mc);
        OpenMapView view = OpenMapView.current();
        if (mc.theWorld == null || mc.thePlayer == null || view == null) return;
        if (mc.currentScreen != null) return;
        boolean open = OpenMapView.takeRequest();
        while (key().isPressed()) open = true;
        if (open) mc.displayGuiScreen(new OpenMapMenuScreen());
    }

    /** Near the map edge, a light haze so the land fades out before the culled area. Terrain fog only. */
    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent e) {
        if (e.fogMode != 0) return; // -1 is the sky: leave it as it is
        OpenMapView view = OpenMapView.current();
        if (view == null || e.entity == null || e.block.getMaterial() != Material.air) return; // water/lava fog stays
        if (e.entity instanceof EntityLivingBase && ((EntityLivingBase) e.entity).isPotionActive(Potion.blindness)) return;
        float end = OpenMapCull.fogEnd(view, e.entity.posX, e.entity.posZ, e.farPlaneDistance);
        if (Float.isNaN(end)) return;
        GlStateManager.setFogStart(OpenMapCull.fogStart(end));
        GlStateManager.setFogEnd(end);
    }

    private void autoOpen(Minecraft mc) {
        if (mc.theWorld != null) {
            autoOpenPending = false;
            return;
        }
        if (mc.currentScreen instanceof KestrelMainMenu || mc.currentScreen instanceof GuiMainMenu) {
            if (++menuTicks < AUTO_OPEN_DELAY) return;
            autoOpenPending = false;
            KestrelMod.LOG.info("[Kestrel] opening the Open Map PvP world (launcher button)");
            OpenMapWorlds.open(mc, mc.currentScreen);
        } else {
            menuTicks = 0;
        }
    }

    /** The lobby banner (drawn, not entities). */
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        OpenMapView v = OpenMapView.current();
        Minecraft mc = Minecraft.getMinecraft();
        if (v == null || mc.theWorld == null || !"LOBBY".equals(v.phase)) return;
        double x = v.labelX - RenderUtil.camX(event.partialTicks);
        double y = v.labelY - RenderUtil.camY(event.partialTicks);
        double z = v.labelZ - RenderUtil.camZ(event.partialTicks);
        if (x * x + y * y + z * z > 64 * 64) return;
        String keyName = Keyboard.getKeyName(key().getKeyCode());
        String[] lines = {
                EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + "NIGHT OPEN MAP PVP",
                EnumChatFormatting.GREEN + "PRESS " + keyName.toUpperCase(java.util.Locale.ROOT) + " TO OPEN THE MENU",
                EnumChatFormatting.GRAY + "Diamond kit vs " + EntityPvpBot.NAME + ": sword, rod, snowballs, planks.",
                EnumChatFormatting.GRAY + "Opponent: " + EnumChatFormatting.WHITE + v.modeLabel + " " + v.effectivePercent + "%"
                        + EnumChatFormatting.GRAY + ", " + EnumChatFormatting.WHITE + v.styleLabel
                        + EnumChatFormatting.GRAY + "   Record " + EnumChatFormatting.WHITE + v.wins + "-" + v.losses,
                EnumChatFormatting.DARK_GRAY + "/openmap help  -  /stopopenmap ends a match",
        };
        FontRenderer font = mc.fontRendererObj;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GL11.glNormal3f(0F, 1F, 0F);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0F, 1F, 0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1F, 0F, 0F);
        float s = 0.018F;
        GlStateManager.scale(-s, -s, s);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        int width = 0;
        for (String l : lines) width = Math.max(width, font.getStringWidth(l));
        int lineH = 11;
        int top = -lineH * lines.length / 2;
        int half = width / 2 + 4;
        GlStateManager.disableTexture2D();
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        w.pos(-half, top - 4, 0.01).color(0.03F, 0.08F, 0.05F, 0.55F).endVertex();
        w.pos(-half, top + lineH * lines.length + 2, 0.01).color(0.03F, 0.08F, 0.05F, 0.55F).endVertex();
        w.pos(half, top + lineH * lines.length + 2, 0.01).color(0.03F, 0.08F, 0.05F, 0.55F).endVertex();
        w.pos(half, top - 4, 0.01).color(0.03F, 0.08F, 0.05F, 0.55F).endVertex();
        t.draw();
        GlStateManager.enableTexture2D();
        for (int i = 0; i < lines.length; i++) {
            font.drawString(lines[i], -font.getStringWidth(lines[i]) / 2, top + i * lineH, 0xFFFFFFFF, false);
        }
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.popMatrix();
    }
}
