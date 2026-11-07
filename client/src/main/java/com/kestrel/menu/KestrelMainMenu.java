package com.kestrel.menu;

import com.kestrel.KestrelClient;
import com.kestrel.gui.ClientSettingsScreen;
import com.kestrel.gui.Theme;
import com.kestrel.skin.AccountSkin;
import com.kestrel.skin.SkinManager;
import com.kestrel.skin.SkinScreen;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.realms.RealmsBridge;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.GuiModList;
import net.minecraft.client.renderer.texture.ITextureObject;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/**
 * Kestrel's main menu, shown instead of Minecraft's (see {@link MenuReplacer}).
 *
 * <p>Layout: the Night mark and a text navigation column on the left; on the
 * right, flowing ribbons and the player's own skin as a 3D character standing
 * above the SKIN button. On the first open of a session a short "click to
 * enter" splash comes first. Each time the menu opens from elsewhere the
 * character runs in from off-screen, slows down, stops and turns to face you
 * ({@link EntranceAnimation}); coming back from a screen opened here (worlds,
 * servers, options, skin) keeps it standing.
 *
 * <p>Above the wordmark a splash text gently pulses, like vanilla's splash. Once
 * the character stands, it can be dragged with the left mouse button to turn it
 * all the way round and tilt the view ({@link ModelOrbit}).
 *
 * <p>Every animation runs on wall-clock time, so it looks the same at any frame
 * rate. Navigation opens the normal Minecraft/Forge screens.
 */
public final class KestrelMainMenu extends GuiScreen implements FullFrameRateScreen {
    /** Whole-number zoom of the virtual canvas (see VirtualScale). */
    private int zoom = 1;
    private static final ResourceLocation MARK = new ResourceLocation("kestrel", "textures/menu/mark.png");
    private static final int MARK_TEX = 128;
    /** Shown once per game session. */
    private static boolean splashDone;

    private static final String[] LABELS = {
            "Singleplayer", "Multiplayer", "Realms", "Mods", "Minecraft Settings", "Night Settings", "Quit Game"};
    private static final int SINGLE = 0, MULTI = 1, REALMS = 2, MODS = 3, OPTIONS = 4, KESTREL = 5, QUIT = 6;
    private static final float LEAVE_SECONDS = 0.16f;
    /** Splash text above the wordmark, its base scale, pulse (fraction of the size) and period (s). */
    private static final String SPLASH = "CODE \"LOVEMOJANG\"";
    private static final float SPLASH_SCALE = 1.3f;
    private static final float SPLASH_PULSE = 0.05f;
    private static final float SPLASH_PERIOD = 1.4f;
    private static final int SPLASH_COLOR = 0xD9C4FF;

    private final KestrelClient client;
    private final SkinManager skins;
    private final PlayerModelRenderer renderer = new PlayerModelRenderer();
    private final PlayerModelRenderer.Pose pose = new PlayerModelRenderer.Pose();
    private final EntranceAnimation.Pose run = new EntranceAnimation.Pose();
    private final float[] hover = new float[LABELS.length];
    private final MenuDiagnostics diagnostics = new MenuDiagnostics("main menu");
    private final ModelOrbit orbit = new ModelOrbit();
    /** Where the character was last drawn, for hit-testing a drag; NaN until it has arrived. */
    private float drawnCharX = Float.NaN;

    /**
     * Diagnostic slow motion ({@code -Dkestrel.animTimeScale=0.05}, shared with
     * the first-person animation); 1 = real time.
     */
    private static final double TIME_SCALE = timeScale();
    private final long createdNanos = now();
    /** When the menu (after the splash) started appearing. -1 while the splash is up. */
    private long enteredNanos = -1;
    /** Whether this menu started with the splash (only then does the mark glide into place). */
    private final boolean withSplash;
    private long lastFrameNanos = -1;
    private float skinHover;
    private float headYaw;
    private float headPitch;
    private final float[] headTarget = new float[2];
    /** GL id of the mark texture whose filtering was set to linear (re-done if a resource reload re-creates it). */
    private int markFilteredId = -1;
    /** Footer text, built once. */
    private String footerLeft;
    private int footerLeftWidth;
    private int footerRightWidth;
    private int splashWidth;
    private int leaving = -1;
    private long leaveNanos;

    // Layout (GUI pixels), computed in initGui.
    private int navX, navY, itemH, navW;
    private float markX, markY, markSize;
    private float charX, feetY, charScale;
    private int skinX, skinY, skinW, skinH;
    /** ACCOUNTS button, beside SKIN (same size and row). */
    private int accX;
    private float accHover;

    public KestrelMainMenu(KestrelClient client, SkinManager skins) {
        this.client = client;
        this.skins = skins;
        withSplash = !splashDone;
        if (splashDone) enteredNanos = createdNanos;
    }

    @Override
    public void initGui() {
        zoom = VirtualScale.apply(this, mc);
        AccountSkin.request(client);
        int w = width;
        int h = height;
        markSize = clamp(h * 0.12f, 26f, 56f);
        navX = Math.max(22, Math.round(w * 0.10f));
        markX = navX;
        markY = Math.max(14f, h * 0.17f);
        itemH = h < 300 ? 16 : 19;
        navY = Math.round(markY + markSize + (h < 300 ? 14 : 24));
        navW = 150;
        // Character: right of centre, tall but leaving room for the name tag and the SKIN button.
        charScale = clamp(h * 0.25f, 40f, 170f);
        feetY = h * 0.78f;
        float minHead = 30f;
        if (feetY - charScale * 2.25f < minHead) charScale = (feetY - minHead) / 2.25f;
        charX = Math.max(navX + navW + charScale * 0.9f, w * 0.64f);
        skinW = 80;
        skinH = 20;
        // SKIN and ACCOUNTS side by side, centred under the character.
        skinX = Math.round(charX - (skinW * 2 + 6) / 2f);
        accX = skinX + skinW + 6;
        skinY = Math.round(feetY + Math.max(8f, charScale * 0.12f));
        if (skinY + skinH > h - 18) skinY = h - 18 - skinH;
        footerLeft = "Night " + client.version();
        footerLeftWidth = fontRendererObj.getStringWidth(footerLeft);
        footerRightWidth = fontRendererObj.getStringWidth(FOOTER_RIGHT);
        splashWidth = fontRendererObj.getStringWidth(SPLASH);
    }

    private static final String FOOTER_RIGHT = "Copyright Mojang AB. Do not distribute!";

    // ---- timing ----------------------------------------------------------------

    private static double timeScale() {
        try {
            double v = Double.parseDouble(System.getProperty("kestrel.animTimeScale", "1"));
            return v >= 0.01 && v <= 1.0 ? v : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private static long now() {
        long n = System.nanoTime();
        return TIME_SCALE == 1.0 ? n : (long) (n * TIME_SCALE);
    }

    private static float secs(long from, long now) {
        return (now - from) / 1.0e9f;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static float easeOut(float v) {
        float i = 1f - clamp01(v);
        return 1f - i * i * i;
    }

    private static float easeInOut(float v) {
        float c = clamp01(v);
        return c < 0.5f ? 4f * c * c * c : 1f - (float) Math.pow(-2f * c + 2f, 3) / 2f;
    }

    /** Frame-rate independent exponential approach. */
    private static float approach(float cur, float target, float rate, float dt) {
        return cur + (target - cur) * (1f - (float) Math.exp(-rate * dt));
    }

    // ---- input -------------------------------------------------------------------

    private void enter() {
        if (enteredNanos < 0) {
            enteredNanos = now();
            splashDone = true;
            click();
        }
    }

    private void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private int itemAt(int mx, int my) {
        for (int i = 0; i < LABELS.length; i++) {
            int y = navY + i * itemH;
            if (mx >= navX - 8 && mx < navX + navW && my >= y - 4 && my < y + itemH - 4) return i;
        }
        return -1;
    }

    private boolean overSkin(int mx, int my) {
        return mx >= skinX && mx < skinX + skinW && my >= skinY && my < skinY + skinH;
    }

    private boolean overAccounts(int mx, int my) {
        return mx >= accX && mx < accX + skinW && my >= skinY && my < skinY + skinH;
    }

    /** On the standing character (its body and head; not the name tag or the buttons below). */
    private boolean overModel(int mx, int my) {
        if (Float.isNaN(drawnCharX)) return false;
        float halfW = charScale * 0.5f;
        return mx >= drawnCharX - halfW && mx <= drawnCharX + halfW
                && my >= feetY - charScale * 2.1f && my <= feetY;
    }

    private boolean ready() {
        return enteredNanos >= 0 && leaving < 0 && secs(enteredNanos, now()) > 0.35f;
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        if (enteredNanos < 0) {
            enter();
            return;
        }
        if (button != 0 || !ready()) return;
        int i = itemAt(mx, my);
        if (i >= 0) {
            click();
            leaving = i;
            leaveNanos = now();
        } else if (overSkin(mx, my)) {
            click();
            mc.displayGuiScreen(new SkinScreen(client, skins, this));
        } else if (overAccounts(mx, my)) {
            click();
            mc.displayGuiScreen(new com.kestrel.account.AccountScreen(client, this));
        } else if (overModel(mx, my)) {
            orbit.press(mx, my);
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long timeSinceLastClick) {
        if (button == 0) orbit.move(mx, my);
    }

    @Override
    protected void mouseReleased(int mx, int my, int button) {
        if (button == 0) orbit.release();
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (enteredNanos < 0) enter(); // any key also enters; Esc does nothing on the main menu
    }

    private void open(int i) {
        switch (i) {
            case SINGLE:
                mc.displayGuiScreen(new GuiSelectWorld(this));
                break;
            case MULTI:
                mc.displayGuiScreen(new GuiMultiplayer(this));
                break;
            case REALMS:
                new RealmsBridge().switchToRealms(this);
                break;
            case MODS:
                mc.displayGuiScreen(new GuiModList(this));
                break;
            case OPTIONS:
                mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
                break;
            case KESTREL:
                mc.displayGuiScreen(new ClientSettingsScreen(client, this));
                break;
            case QUIT:
                mc.shutdown();
                break;
            default:
                break;
        }
    }

    @Override
    public void onGuiClosed() {
        leaving = -1;
        orbit.release();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- drawing -----------------------------------------------------------------

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        long t0 = diagnostics.begin();
        GlStateManager.pushMatrix();
        GlStateManager.scale((float) zoom, (float) zoom, 1f);
        try {
            drawScaled(mx / zoom, my / zoom, partialTicks);
        } finally {
            GlStateManager.popMatrix();
        }
        diagnostics.end(t0);
    }

    private void drawScaled(int mx, int my, float partialTicks) {
        long now = now();
        float dt = lastFrameNanos < 0 ? 0f : Math.min(0.1f, secs(lastFrameNanos, now));
        lastFrameNanos = now;
        float time = secs(createdNanos, now);
        boolean splash = enteredNanos < 0;
        float e = splash ? 0f : secs(enteredNanos, now);

        // The menu opened from elsewhere fades in from black; after the splash it is already lit.
        float ribbons = splash ? 0f : easeOut((e - 0.15f) / 0.9f);
        MenuBackground.draw(width, height, time, ribbons, charX, feetY - charScale, charScale * 1.9f);

        drawMark(splash, time, e);
        if (!splash) drawCodeSplash(time, e);
        if (splash) {
            drawSplashHint(time);
        } else {
            drawNav(mx, my, e, dt);
            drawCharacter(mx, my, e, dt, time);
            drawFooter(e);
        }

        if (leaving >= 0) {
            float l = secs(leaveNanos, now) / LEAVE_SECONDS;
            MenuBackground.gradientRect(0, 0, width, height, 0x0B0714, clamp01(l), clamp01(l));
            if (l >= 1f) {
                int target = leaving;
                leaving = -1;
                open(target);
            }
        } else {
            float fadeIn = 1f - clamp01(time / 0.35f);
            if (fadeIn > 0f) MenuBackground.gradientRect(0, 0, width, height, 0x0B0714, fadeIn, fadeIn);
        }
    }

    /** Night mark plus the wordmark: centred and large on the splash, then it settles top left. */
    private void drawMark(boolean splash, float time, float e) {
        float move = splash ? 0f : (withSplash ? easeInOut(e / 0.7f) : 1f);
        float bigSize = clamp(height * 0.22f, 48f, 120f);
        float sx = width / 2f - bigSize / 2f;
        float sy = height * 0.40f - bigSize / 2f;
        // On the splash the mark breathes gently; it eases into its menu place on entering.
        float breathe = splash ? 1f + 0.025f * (float) Math.sin(time * 2.2f) : 1f;
        float size = (bigSize + (markSize - bigSize) * move) * breathe;
        float x = sx + (markX - sx) * move - (size - (bigSize + (markSize - bigSize) * move)) / 2f;
        float y = sy + (markY - sy) * move - (size - (bigSize + (markSize - bigSize) * move)) / 2f;
        float alpha = splash ? clamp01(time / 0.6f) : (withSplash ? 1f : easeOut(e / 0.4f));

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        // Soft glow behind the mark.
        drawRadial(x + size / 2f, y + size / 2f, size * 1.1f, 0x8652D9, 0.16f * alpha);
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, alpha);
        mc.getTextureManager().bindTexture(MARK);
        // Filtering is texture state: set it once per texture object, not every frame.
        ITextureObject markTex = mc.getTextureManager().getTexture(MARK);
        int markId = markTex != null ? markTex.getGlTextureId() : -1;
        if (markId != markFilteredId) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            markFilteredId = markId;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(size / MARK_TEX, size / MARK_TEX, 1f);
        Gui.drawModalRectWithCustomSizedTexture(0, 0, 0f, 0f, MARK_TEX, MARK_TEX, MARK_TEX, MARK_TEX);
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);

        // Wordmark: under the mark on the splash, beside it in the menu.
        float textScale = 2f;
        String word = "NIGHT";
        int ww = spacedWidth(word, 2);
        float splashTx = width / 2f - ww * textScale / 2f;
        float splashTy = sy + bigSize + 14f;
        float menuTx = markX + markSize + 10f;
        float menuTy = markY + markSize / 2f - 11f;
        float tx = splashTx + (menuTx - splashTx) * move;
        float ty = splashTy + (menuTy - splashTy) * move;
        if (alpha > 0.03f) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(tx, ty, 0f);
            GlStateManager.scale(textScale, textScale, 1f);
            drawSpaced(word, 0, 0, argb(Theme.TEXT, alpha), 2);
            GlStateManager.popMatrix();
            String sub = "CLIENT  ·  1.8.9";
            float subX = tx + (ww * textScale - spacedWidth(sub, 1)) * (1f - move) / 2f;
            drawSpaced(sub, Math.round(subX), Math.round(ty + 9 * textScale + 4), argb(Theme.ACCENT, alpha * 0.9f), 1);
        }
    }

    /**
     * The splash text, centred over the wordmark and kept above the mark, pulsing
     * smoothly (a sine of wall-clock time) around its centre like vanilla's splash.
     */
    private void drawCodeSplash(float time, float e) {
        float a = withSplash ? easeOut((e - 0.6f) / 0.5f) : easeOut((e - 0.2f) / 0.5f);
        if (a < 0.03f) return;
        float phase = (time % SPLASH_PERIOD) / SPLASH_PERIOD * (float) (Math.PI * 2.0);
        float s = SPLASH_SCALE * (1f + SPLASH_PULSE * (float) Math.sin(phase));
        float maxS = SPLASH_SCALE * (1f + SPLASH_PULSE);
        float halfH = 4.5f * maxS;
        float halfW = splashWidth * maxS / 2f;
        float cx = markX + markSize + 10f + spacedWidth("NIGHT", 2); // centre of the 2x wordmark
        cx = clamp(cx, 4f + halfW, width - 4f - halfW);
        float cy = Math.max(markY - 4f - halfH, 3f + halfH);
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0f);
        GlStateManager.scale(s, s, 1f);
        GlStateManager.enableBlend();
        fontRendererObj.drawString(SPLASH, -splashWidth / 2f, -4f, argb(SPLASH_COLOR, a), true);
        GlStateManager.popMatrix();
    }

    private void drawSplashHint(float time) {
        float a = clamp01((time - 0.5f) / 0.5f) * (0.55f + 0.45f * (float) Math.sin(time * 3.0f));
        if (a < 0.03f) return;
        String hint = "CLICK TO ENTER";
        int w = spacedWidth(hint, 2);
        float bigSize = clamp(height * 0.22f, 48f, 120f);
        float below = height * 0.40f + bigSize / 2f + 14f + 18f + 14f + 16f;
        drawSpaced(hint, (width - w) / 2, Math.round(Math.max(height * 0.72f, below)), argb(Theme.MUTED, a), 2);
    }

    private void drawNav(int mx, int my, float e, float dt) {
        int hot = ready() ? itemAt(mx, my) : -1;
        // Thin divider under the wordmark.
        float lineIn = easeOut((e - 0.3f) / 0.5f);
        if (lineIn > 0f) {
            MenuBackground.gradientRect(navX, navY - 12, navX + navW * 0.55f * lineIn, navY - 11, 0x4A3866, 0.5f, 0.5f);
        }
        for (int i = 0; i < LABELS.length; i++) {
            float in = easeOut((e - 0.35f - i * 0.06f) / 0.45f);
            if (in <= 0.02f) continue;
            hover[i] = approach(hover[i], i == hot ? 1f : 0f, 14f, dt);
            float hv = hover[i];
            int y = navY + i * itemH;
            float slide = (1f - in) * -16f + hv * 6f;
            // Accent bar grows in on hover.
            if (hv > 0.02f) {
                MenuBackground.gradientRect(navX - 8, y - 1, navX - 6, y + 9, Theme.ACCENT & 0xFFFFFF, hv * in, hv * in);
            }
            int base = i == QUIT ? 0xD9A6B4 : 0xDDD2F0;
            int col = lerpRgb(base, i == QUIT ? 0xFF8A8A : 0xFFFFFF, hv);
            drawSpaced(LABELS[i], Math.round(navX + slide), y, argb(col, in), 1);
        }
    }

    private void drawCharacter(int mx, int my, float e, float dt, float time) {
        float start = 0.55f;
        float t = e - start;
        if (t < 0f) return;
        EntranceAnimation.pose(t, run);
        float offX = width - charX + charScale * 1.2f;
        float x = charX + (1f - run.progress) * offX;

        // Body pose: run cycle, easing into a relaxed idle.
        pose.reset();
        float sin = (float) Math.sin(run.phase);
        pose.rightLegX = sin * 1.1f * run.stride;
        pose.leftLegX = -pose.rightLegX;
        pose.rightArmX = -sin * 1.0f * run.stride;
        pose.leftArmX = -pose.rightArmX;
        pose.rightArmZ = 0.10f * run.stride;
        pose.leftArmZ = -0.10f * run.stride;
        // Mouse rotation (and its idle sway) once the character stands; a release outside
        // the window, or losing focus, ends a drag as a normal release would.
        if (orbit.dragging() && (!Mouse.isButtonDown(0) || !Display.isActive())) orbit.release();
        orbit.update(dt);
        pose.yaw = run.yaw + orbit.yaw() * run.idle;
        pose.viewPitch = orbit.pitch() * run.idle;
        pose.lean = run.lean;
        pose.bob = run.bob;
        if (run.idle > 0f) {
            float breath = (float) Math.sin(time * 1.6f);
            pose.rightArmZ += run.idle * (0.045f + 0.025f * breath);
            pose.leftArmZ -= run.idle * (0.045f + 0.025f * breath);
            pose.rightArmX += run.idle * 0.03f * (float) Math.sin(time * 1.1f);
            pose.leftArmX -= run.idle * 0.03f * (float) Math.sin(time * 1.1f);
            pose.bob += run.idle * 0.006f * (breath + 1f);
        }
        // Once settled the head looks at the pointer; it eases back to facing forward
        // while running or when the pointer leaves the window. Head only: the body pose is untouched.
        float targetYaw = 0f;
        float targetPitch = 0f;
        // Looking at the pointer fades out as the body is turned away or the view tilted.
        float look = run.idle * Math.max(0f, (float) Math.cos(Math.toRadians(orbit.yaw())))
                * (float) Math.cos(Math.toRadians(orbit.pitch()));
        if (look > 0f && Mouse.isInsideWindow()) {
            // Centre of the head (eye level), in the same GUI units as the pointer.
            float headY = feetY - pose.bob * charScale - charScale * 1.75f;
            HeadLook.target(mx - x, my - headY, charScale * 2.5f, pose.yaw, headTarget);
            targetYaw = headTarget[0] * look;
            targetPitch = headTarget[1] * look;
        }
        headYaw = approach(headYaw, targetYaw, HeadLook.RATE, dt);
        headPitch = approach(headPitch, targetPitch, HeadLook.RATE, dt);
        pose.headYaw = headYaw;
        pose.headPitch = headPitch;

        float shadowA = 0.35f * (0.6f + 0.4f * (1f - run.bob * 8f));
        MenuBackground.shadow(x, feetY + 1f, charScale * 0.42f, charScale * 0.09f, shadowA);

        boolean slim;
        ResourceLocation tex = skins.activeTexture();
        if (tex != null) {
            slim = skins.activeSlim();
        } else {
            tex = AccountSkin.texture();
            slim = AccountSkin.slim();
        }
        renderer.draw(mc, x, feetY, charScale, tex, slim, pose, client.capes().menuPreviewTexture());
        drawnCharX = run.idle >= 0.99f ? x : Float.NaN;

        // Name tag above the head, like in game.
        String name = mc.getSession().getUsername();
        int nw = fontRendererObj.getStringWidth(name);
        float tagY = feetY - pose.bob * charScale - charScale * 2.05f - 14f;
        float tagIn = clamp01(t / 0.3f);
        GlStateManager.enableBlend();
        Gui.drawRect(Math.round(x - nw / 2f - 3), Math.round(tagY - 2), Math.round(x + nw / 2f + 3), Math.round(tagY + 9),
                argb(0x000000, 0.35f * tagIn));
        fontRendererObj.drawString(name, Math.round(x - nw / 2f), Math.round(tagY), argb(0xFFFFFF, Math.max(0.05f, tagIn)), false);

        // SKIN and ACCOUNTS buttons, tied to the character: they appear once it has arrived.
        float btnIn = easeOut((t - EntranceAnimation.ARRIVE + 0.1f) / 0.45f);
        if (btnIn > 0.02f) {
            skinHover = approach(skinHover, ready() && overSkin(mx, my) ? 1f : 0f, 14f, dt);
            accHover = approach(accHover, ready() && overAccounts(mx, my) ? 1f : 0f, 14f, dt);
            drawCharButton("SKIN", skinX, skinHover, btnIn);
            drawCharButton("ACCOUNTS", accX, accHover, btnIn);
        }
    }

    private void drawCharButton(String label, int bx, float hover, float btnIn) {
        float lift = (1f - btnIn) * 6f;
        int by = Math.round(skinY + lift);
        MenuBackground.gradientRect(bx, by, bx + skinW, by + skinH, lerpRgb(0x25183B, 0x8652D9, hover),
                0.85f * btnIn, 0.85f * btnIn);
        // Thin outline.
        int edge = argb(lerpRgb(0x4A3866, 0xC6A4FF, hover), btnIn);
        Gui.drawRect(bx, by, bx + skinW, by + 1, edge);
        Gui.drawRect(bx, by + skinH - 1, bx + skinW, by + skinH, edge);
        Gui.drawRect(bx, by, bx + 1, by + skinH, edge);
        Gui.drawRect(bx + skinW - 1, by, bx + skinW, by + skinH, edge);
        int spacing = spacedWidth(label, 3) + 6 <= skinW ? 3 : 1;
        int lw = spacedWidth(label, spacing);
        drawSpaced(label, bx + (skinW - lw) / 2, by + (skinH - 8) / 2, argb(0xFFFFFF, btnIn), spacing);
    }

    private void drawFooter(float e) {
        float a = easeOut((e - 0.8f) / 0.6f) * 0.75f;
        if (a < 0.03f) return;
        fontRendererObj.drawString(footerLeft, navX, height - 14, argb(0x817391, a), false);
        int rw = footerRightWidth;
        // Only when there is room beside the version (narrow windows, big GUI scales).
        if (width - rw - 8 > navX + footerLeftWidth + 16) {
            fontRendererObj.drawString(FOOTER_RIGHT, width - rw - 8, height - 14, argb(0x5E5270, a), false);
        }
    }

    // ---- small helpers -----------------------------------------------------------

    private static void drawRadial(float x, float y, float r, int rgb, float a) {
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        GlStateManager.disableCull();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        MenuBackground.smooth();
        MenuBackground.radial(t.getWorldRenderer(), t, x, y, r, rgb, a, 32);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
    }

    /** ARGB with alpha; never below the font renderer's "treat as opaque" threshold. */
    private static int argb(int rgb, float a) {
        int al = Math.round(clamp01(a) * 255f);
        if (al < 5) al = 5;
        return al << 24 | (rgb & 0xFFFFFF);
    }

    private static int lerpRgb(int a, int b, float t) {
        t = clamp01(t);
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private int spacedWidth(String s, int spacing) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += fontRendererObj.getCharWidth(s.charAt(i)) + (i + 1 < s.length() ? spacing : 0);
        return w;
    }

    /** Text with extra letter spacing (cleaner at small sizes than the stock font). */
    private void drawSpaced(String s, int x, int y, int color, int spacing) {
        if (spacing <= 0) {
            fontRendererObj.drawString(s, x, y, color, false);
            return;
        }
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            fontRendererObj.drawString(c < CHARS.length ? CHARS[c] : String.valueOf(c), cx, y, color, false);
            cx += fontRendererObj.getCharWidth(c) + spacing;
        }
    }

    /** One-character strings for ASCII and Latin-1, so drawing spaced text allocates nothing. */
    private static final String[] CHARS = new String[256];

    static {
        for (int i = 0; i < CHARS.length; i++) CHARS[i] = String.valueOf((char) i);
    }
}
