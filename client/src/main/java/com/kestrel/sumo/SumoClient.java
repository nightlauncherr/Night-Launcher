package com.kestrel.sumo;

import com.kestrel.KestrelMod;
import com.kestrel.input.InputManager;
import com.kestrel.menu.KestrelMainMenu;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * Client side of Sumo practice: the M key for the Sumo menu, opening the menu
 * when the session asks (joining the lobby, a finished match), the floating
 * lobby text, and the launcher's SUMO button ({@code -Dkestrel.openSumo=true}),
 * which opens the Sumo world as soon as the main menu is up.
 */
public final class SumoClient {
    /** Set by the launcher's SUMO button. */
    static final String OPEN_SUMO_PROPERTY = "kestrel.openSumo";
    /** Main menu ticks before opening the world (lets the menu settle once). */
    private static final int AUTO_OPEN_DELAY = 10;

    private final KeyBinding menuKey = new KeyBinding("Sumo menu (Sumo practice)", Keyboard.KEY_M, InputManager.CATEGORY);
    private boolean autoOpenPending = Boolean.getBoolean(OPEN_SUMO_PROPERTY);
    private int menuTicks;

    SumoClient() {
        ClientRegistry.registerKeyBinding(menuKey);
        if (autoOpenPending) KestrelMod.LOG.info("[Kestrel] launched with the SUMO button: the Sumo world opens from the main menu");
    }

    KeyBinding menuKey() {
        return menuKey;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (autoOpenPending) autoOpen(mc);
        SumoView view = SumoView.current();
        if (mc.theWorld == null || mc.thePlayer == null || view == null) {
            while (menuKey.isPressed()) {
                // not in the Sumo world: the key does nothing
            }
            return;
        }
        if (mc.currentScreen != null) return;
        boolean open = false;
        SumoView.Screen request = SumoView.takeRequest();
        if (request != null) open = true;
        while (menuKey.isPressed()) open = true;
        if (open) mc.displayGuiScreen(new SumoMenuScreen(this));
    }

    private void autoOpen(Minecraft mc) {
        if (mc.theWorld != null) {
            autoOpenPending = false; // already in a world some other way
            return;
        }
        if (mc.currentScreen instanceof KestrelMainMenu || mc.currentScreen instanceof GuiMainMenu) {
            if (++menuTicks < AUTO_OPEN_DELAY) return;
            autoOpenPending = false;
            KestrelMod.LOG.info("[Kestrel] opening the Sumo practice world (SUMO button)");
            SumoWorlds.open(mc, new GuiSelectWorld(mc.currentScreen));
        } else {
            menuTicks = 0;
        }
    }

    /** The floating title over the lobby (drawn, not entities, so nothing is ever saved into the world). */
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        SumoView v = SumoView.current();
        Minecraft mc = Minecraft.getMinecraft();
        if (v == null || mc.theWorld == null || !"LOBBY".equals(v.phase)) return;
        double x = v.labelX - RenderUtil.camX(event.partialTicks);
        double y = v.labelY - RenderUtil.camY(event.partialTicks);
        double z = v.labelZ - RenderUtil.camZ(event.partialTicks);
        if (x * x + y * y + z * z > 64 * 64) return;
        String key = Keyboard.getKeyName(menuKey.getKeyCode());
        String[] lines = {
                EnumChatFormatting.LIGHT_PURPLE + "" + EnumChatFormatting.BOLD + "NIGHT SUMO PRACTICE",
                EnumChatFormatting.WHITE + "Press " + EnumChatFormatting.LIGHT_PURPLE + key + EnumChatFormatting.WHITE + " and choose START SUMO",
                EnumChatFormatting.GRAY + "Knock " + EntitySumoBot.NAME + " off the platform; don't fall yourself.",
                EnumChatFormatting.GRAY + "Opponent: " + EnumChatFormatting.WHITE + v.modeLabel + " " + v.effectivePercent + "%"
                        + EnumChatFormatting.GRAY + ", " + EnumChatFormatting.WHITE + v.styleLabel
                        + EnumChatFormatting.GRAY + "   Record " + EnumChatFormatting.WHITE + v.wins + "-" + v.losses,
                EnumChatFormatting.DARK_GRAY + "/sumo help  -  /sumo edit to build the arena",
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
        w.pos(-half, top - 4, 0.01).color(0.05F, 0.03F, 0.09F, 0.55F).endVertex();
        w.pos(-half, top + lineH * lines.length + 2, 0.01).color(0.05F, 0.03F, 0.09F, 0.55F).endVertex();
        w.pos(half, top + lineH * lines.length + 2, 0.01).color(0.05F, 0.03F, 0.09F, 0.55F).endVertex();
        w.pos(half, top - 4, 0.01).color(0.05F, 0.03F, 0.09F, 0.55F).endVertex();
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
