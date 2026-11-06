package com.kestrel.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kestrel.KestrelClient;
import com.kestrel.KestrelMod;
import com.kestrel.gui.Theme;
import com.kestrel.gui.widgets.ActionButton;
import com.kestrel.menu.FullFrameRateScreen;
import com.kestrel.menu.MenuBackground;
import com.kestrel.menu.PlayerModelRenderer;
import com.kestrel.menu.VirtualScale;
import com.kestrel.skin.AccountSkin;
import com.kestrel.util.AsyncLoader;
import com.kestrel.util.RenderUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The in-game account switcher, opened from the ACCOUNTS button under the main
 * menu's character. It shows the accounts saved in Night Launcher (the same
 * list as the launcher's Accounts page), switches the running game to one of
 * them, and adds new Microsoft accounts, all through the launcher that started
 * the game ({@link AccountBridge}); sign-in itself always happens in the
 * launcher with Microsoft. Switching is only offered here, on the title screen,
 * so the game is never connected to a server while its identity changes.
 */
public final class AccountScreen extends GuiScreen implements FullFrameRateScreen {
    private static final int ROW_H = 30;
    /** Faces looked up this game session (account UUID → skin texture). */
    private static final Map<String, ResourceLocation> FACES = new HashMap<String, ResourceLocation>();
    private static final Map<String, Boolean> FACE_REQUESTED = new HashMap<String, Boolean>();

    private final KestrelClient client;
    private final GuiScreen parent;
    private final PlayerModelRenderer renderer = new PlayerModelRenderer();
    private final PlayerModelRenderer.Pose pose = new PlayerModelRenderer.Pose();
    private final long openedNanos = System.nanoTime();
    private final List<ActionButton> buttons = new ArrayList<ActionButton>();
    private final List<Row> rows = new ArrayList<Row>();

    private static final class Row {
        String name;
        String uuid;
        boolean needsSignIn;
    }

    private int zoom = 1;
    private int colX, colW, headerY, listY, listH;
    private float charX, feetY, charScale;
    private ActionButton addButton;
    private ActionButton cancelButton;

    private boolean loading;
    private String launcherActive;
    /** Request in flight: switching (its target name) or adding. */
    private String switchingTo;
    private int addRequest = -1;
    private String code;
    private String codeUri;
    private String message;
    private boolean messageOk;

    public AccountScreen(KestrelClient client, GuiScreen parent) {
        this.client = client;
        this.parent = parent;
    }

    private AccountBridge bridge() {
        return client.accounts();
    }

    @Override
    public void initGui() {
        zoom = VirtualScale.apply(this, mc);
        Keyboard.enableRepeatEvents(false);
        colX = Math.max(14, Math.round(width * 0.06f));
        colW = Math.min(280, Math.round(width * 0.56f) - colX);
        headerY = Math.max(10, Math.round(height * 0.07f));
        charScale = Math.max(36f, Math.min(150f, height * 0.25f));
        feetY = height * 0.80f;
        if (feetY - charScale * 2.25f < 26f) charScale = (feetY - 26f) / 2.25f;
        charX = Math.max(colX + colW + charScale * 0.9f, width * 0.72f);

        buttons.clear();
        listY = headerY + 46;
        int bottom = height - 34;
        listH = Math.max(ROW_H * 2, bottom - 64 - listY);
        int half = (colW - 4) / 2;
        addButton = add(new ActionButton("Add Microsoft account", false, this::addAccount), colX, bottom - 24, half, 18);
        cancelButton = add(new ActionButton("Cancel sign-in", false, this::cancelAdd), colX + half + 4, bottom - 24,
                colW - half - 4, 18);
        add(new ActionButton("Back", false, () -> mc.displayGuiScreen(parent)), colX, bottom, 60, 18);
        add(new ActionButton("Refresh", false, this::refresh), colX + 64, bottom, 60, 18);
        AccountSkin.request(client);
        if (rows.isEmpty() && !loading) refresh();
    }

    private ActionButton add(ActionButton b, int x, int y, int w, int h) {
        b.x = x;
        b.y = y;
        b.w = w;
        b.h = h;
        buttons.add(b);
        return b;
    }

    private void say(boolean ok, String text) {
        messageOk = ok;
        message = text;
    }

    // ---- requests -------------------------------------------------------

    private void refresh() {
        if (!bridge().available()) return;
        loading = true;
        bridge().request("accounts", null, this::onAccounts);
    }

    private void onAccounts(JsonObject r) {
        loading = false;
        if (!"accounts".equals(str(r, "type"))) {
            say(false, str(r, "message"));
            return;
        }
        rows.clear();
        launcherActive = str(r, "active");
        JsonElement list = r.get("accounts");
        if (list != null && list.isJsonArray()) {
            JsonArray a = list.getAsJsonArray();
            for (JsonElement e : a) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                Row row = new Row();
                row.name = str(o, "name");
                row.uuid = str(o, "uuid");
                JsonElement n = o.get("needsSignIn");
                row.needsSignIn = n != null && n.isJsonPrimitive() && n.getAsBoolean();
                if (row.name != null && row.uuid != null && row.uuid.matches("[0-9a-f]{32}")) rows.add(row);
            }
        }
    }

    private void switchTo(final Row row) {
        if (switchingTo != null || addRequest >= 0) return;
        if (mc.theWorld != null) {
            say(false, "Leave the world before switching accounts.");
            return;
        }
        if (row.needsSignIn) {
            say(false, row.name + "'s sign-in has expired. Use Add Microsoft account to sign in to it again.");
            return;
        }
        switchingTo = row.name;
        say(true, "Signing in as " + row.name + "...");
        int n = bridge().request("switch", row.uuid, this::onSession);
        if (n < 0) {
            switchingTo = null;
            say(false, "Night Launcher is not running.");
        }
    }

    private void onSession(JsonObject r) {
        String target = switchingTo;
        switchingTo = null;
        if (!"session".equals(str(r, "type"))) {
            say(false, nonEmpty(str(r, "message"), "Switching failed. Nothing was changed."));
            refresh();
            return;
        }
        SessionSwap.NewSession s = SessionSwap.validate(str(r, "name"), str(r, "uuid"), str(r, "token"));
        if (s == null) {
            say(false, "The launcher sent an invalid session. Nothing was changed.");
            return;
        }
        try {
            SessionSwap.apply(mc, s);
        } catch (IllegalStateException e) {
            KestrelMod.LOG.warn("[Kestrel] account switch failed: {}", e.getMessage());
            say(false, "Could not switch: " + e.getMessage() + ". You are still " + mc.getSession().getUsername() + ".");
            return;
        }
        client.onSessionChanged();
        KestrelMod.LOG.info("[Kestrel] now playing as {}", s.name);
        say(true, "Now playing as " + s.name + ". Servers you join use this account.");
        if (target != null && !target.equals(s.name)) KestrelMod.LOG.info("[Kestrel] account renamed to {}", s.name);
        refresh();
    }

    private void addAccount() {
        if (switchingTo != null || addRequest >= 0) return;
        code = null;
        say(true, "Asking Microsoft for a sign-in code...");
        addRequest = bridge().request("add", null, this::onAdd);
        if (addRequest < 0) say(false, "Night Launcher is not running.");
    }

    private void onAdd(JsonObject r) {
        String type = str(r, "type");
        if ("code".equals(type)) {
            code = str(r, "code");
            codeUri = str(r, "uri");
            say(true, "Sign in on Microsoft's page with this code. This screen updates when you're done.");
            return;
        }
        addRequest = -1;
        code = null;
        if ("added".equals(type)) {
            say(true, "Added " + str(r, "name") + ". Click it to play as it.");
        } else {
            say(false, nonEmpty(str(r, "message"), "Sign-in failed."));
        }
        refresh();
    }

    private void cancelAdd() {
        if (addRequest < 0) return;
        bridge().request("cancel", Integer.toString(addRequest), r -> { });
    }

    private void openCodePage() {
        if (codeUri == null) return;
        // Only Microsoft's own sign-in pages (the launcher validated this already).
        if (!codeUri.startsWith("https://www.microsoft.com/") && !codeUri.startsWith("https://microsoft.com/")
                && !codeUri.startsWith("https://login.live.com/") && !codeUri.startsWith("https://login.microsoftonline.com/")) {
            return;
        }
        if (code != null) GuiScreen.setClipboardString(code);
        Sys.openURL(codeUri);
        say(true, "Opened " + codeUri + " in your browser. The code is on your clipboard.");
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String nonEmpty(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    @Override
    public void onGuiClosed() {
        if (addRequest >= 0) cancelAdd();
    }

    // ---- input ----------------------------------------------------------

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        for (ActionButton b : buttons) {
            if (b.mouseClicked(mx, my, button)) return;
        }
        if (button == 0 && code != null && overCode(mx, my)) {
            openCodePage();
            return;
        }
        if (button == 0 && mx >= colX && mx < colX + colW && my >= listY && my < listY + listH) {
            int i = (my - listY) / ROW_H;
            if (i >= 0 && i < rows.size()) {
                Row row = rows.get(i);
                if (!row.uuid.equals(currentUuid())) switchTo(row);
            }
            return;
        }
        super.mouseClicked(mx, my, button);
    }

    private String currentUuid() {
        String id = mc.getSession().getPlayerID();
        return id == null ? "" : id.replace("-", "").toLowerCase();
    }

    private int codeY() {
        return listY + Math.min(rows.size(), listH / ROW_H) * ROW_H + 8;
    }

    private boolean overCode(int mx, int my) {
        int y = codeY();
        return mx >= colX && mx < colX + colW && my >= y && my < y + 44;
    }

    // ---- faces ----------------------------------------------------------

    private ResourceLocation face(final String uuid, final String name) {
        ResourceLocation tex = FACES.get(uuid);
        if (tex != null) return tex;
        if (!FACE_REQUESTED.containsKey(uuid)) {
            FACE_REQUESTED.put(uuid, Boolean.TRUE);
            final UUID id = toUuid(uuid);
            if (id != null) {
                client.loader().submit(new Callable<GameProfile>() {
                    @Override
                    public GameProfile call() {
                        return Minecraft.getMinecraft().getSessionService().fillProfileProperties(new GameProfile(id, name), false);
                    }
                }, new AsyncLoader.Callback<GameProfile>() {
                    @Override
                    public void done(GameProfile filled, Throwable error) {
                        if (error != null || filled == null) return;
                        try {
                            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> m =
                                    Minecraft.getMinecraft().getSkinManager().loadSkinFromCache(filled);
                            MinecraftProfileTexture t = m.get(MinecraftProfileTexture.Type.SKIN);
                            if (t != null) {
                                FACES.put(uuid, Minecraft.getMinecraft().getSkinManager().loadSkin(t, MinecraftProfileTexture.Type.SKIN));
                            }
                        } catch (RuntimeException e) {
                            KestrelMod.LOG.debug("[Kestrel] no face for an account: {}", e.toString());
                        }
                    }
                });
            }
        }
        UUID id = toUuid(uuid);
        return id != null ? DefaultPlayerSkin.getDefaultSkin(id) : DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    private static UUID toUuid(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{32}")) return null;
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20));
    }

    private void drawFace(ResourceLocation tex, int x, int y, int size) {
        mc.getTextureManager().bindTexture(tex);
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        Gui.drawScaledCustomSizeModalRect(x, y, 8f, 8f, 8, 8, size, size, 64f, 64f);
        Gui.drawScaledCustomSizeModalRect(x, y, 40f, 8f, 8, 8, size, size, 64f, 64f);
    }

    // ---- drawing --------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.scale((float) zoom, (float) zoom, 1f);
        try {
            drawScaled(mouseX / zoom, mouseY / zoom);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawScaled(int mx, int my) {
        float time = (System.nanoTime() - openedNanos) / 1.0e9f;
        MenuBackground.draw(width, height, time, 1f, charX, feetY - charScale, charScale * 1.9f);

        GlStateManager.pushMatrix();
        GlStateManager.translate(colX, headerY, 0f);
        GlStateManager.scale(2f, 2f, 1f);
        fontRendererObj.drawString("ACCOUNTS", 0, 0, Theme.TEXT, false);
        GlStateManager.popMatrix();
        fontRendererObj.drawString("Accounts saved in Night Launcher. Click one to play as it.", colX, headerY + 20,
                Theme.MUTED, false);
        fontRendererObj.drawString(trim("Playing as " + mc.getSession().getUsername(), colW), colX, headerY + 32,
                Theme.OK, false);

        boolean available = bridge().available();
        String current = currentUuid();
        if (!available) {
            RenderUtil.softRect(colX, listY, colX + colW, listY + 52, Theme.CARD);
            List<String> lines = fontRendererObj.listFormattedStringToWidth(
                    "Account switching needs Night Launcher. Start Minecraft from Night Launcher and keep the launcher open "
                            + "(turn off \"Close the launcher once the game has loaded\").", colW - 12);
            int y = listY + 6;
            for (String l : lines) {
                fontRendererObj.drawString(l, colX + 6, y, Theme.WARN, false);
                y += 10;
            }
        } else if (loading && rows.isEmpty()) {
            fontRendererObj.drawString("Loading accounts...", colX, listY + 4, Theme.MUTED, false);
        } else if (rows.isEmpty()) {
            fontRendererObj.drawString("No accounts saved yet. Add one below.", colX, listY + 4, Theme.MUTED, false);
        }

        int visible = Math.min(rows.size(), listH / ROW_H);
        for (int i = 0; i < visible; i++) {
            Row row = rows.get(i);
            int y = listY + i * ROW_H;
            boolean isCurrent = row.uuid.equals(current);
            boolean hover = !isCurrent && switchingTo == null && mx >= colX && mx < colX + colW && my >= y && my < y + ROW_H - 4;
            RenderUtil.softRect(colX, y, colX + colW, y + ROW_H - 4,
                    isCurrent ? 0xFF3A2560 : (hover ? Theme.CARD_HOVER : Theme.CARD));
            if (isCurrent) RenderUtil.outline(colX, y, colX + colW, y + ROW_H - 4, Theme.ACCENT);
            else if (hover) RenderUtil.outline(colX, y, colX + colW, y + ROW_H - 4, Theme.ACCENT_DIM);
            drawFace(face(row.uuid, row.name), colX + 5, y + 3, 20);
            fontRendererObj.drawString(trim(row.name, colW - 110), colX + 32, y + 4, Theme.TEXT, false);
            String sub;
            int subCol;
            if (isCurrent) {
                sub = "Playing as this account";
                subCol = Theme.OK;
            } else if (row.needsSignIn) {
                sub = "Sign-in expired";
                subCol = Theme.WARN;
            } else if (row.name.equals(switchingTo)) {
                sub = "Switching...";
                subCol = Theme.LAVENDER;
            } else {
                sub = "Click to switch";
                subCol = Theme.MUTED;
            }
            fontRendererObj.drawString(sub, colX + 32, y + 14, subCol, false);
            if (row.uuid.equals(launcherActive)) {
                String tag = "LAUNCHER";
                int tw = fontRendererObj.getStringWidth(tag);
                fontRendererObj.drawString(tag, colX + colW - tw - 6, y + 9, Theme.LAVENDER, false);
            }
        }

        if (code != null) {
            int y = codeY();
            boolean hover = overCode(mx, my);
            RenderUtil.softRect(colX, y, colX + colW, y + 44, hover ? Theme.CARD_HOVER : Theme.CARD);
            RenderUtil.outline(colX, y, colX + colW, y + 44, Theme.ACCENT);
            fontRendererObj.drawString("Code (click to open the page and copy it):", colX + 6, y + 4, Theme.MUTED, false);
            GlStateManager.pushMatrix();
            GlStateManager.translate(colX + 6, y + 16, 0f);
            GlStateManager.scale(2f, 2f, 1f);
            fontRendererObj.drawString(code, 0, 0, Theme.LAVENDER, false);
            GlStateManager.popMatrix();
            if (codeUri != null) {
                fontRendererObj.drawString(trim(codeUri, colW - 12), colX + 6, y + 34, Theme.MUTED, false);
            }
        }

        boolean idle = available && switchingTo == null && addRequest < 0;
        addButton.enabled = idle;
        cancelButton.enabled = addRequest >= 0;
        for (ActionButton b : buttons) b.draw(mc, mx, my);

        if (message != null) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth(message, colW);
            int y = height - 34 - 24 - 6 - lines.size() * 10;
            for (String l : lines) {
                fontRendererObj.drawString(l, colX, y, messageOk ? Theme.OK : Theme.ERR, false);
                y += 10;
            }
        }

        // The current account's character on the right, as on the main menu.
        ResourceLocation tex = AccountSkin.texture();
        MenuBackground.shadow(charX, feetY + 1f, charScale * 0.42f, charScale * 0.09f, 0.35f);
        float breath = (float) Math.sin(time * 1.6f);
        pose.reset();
        pose.rightArmZ = 0.045f + 0.025f * breath;
        pose.leftArmZ = -pose.rightArmZ;
        pose.bob = 0.006f * (breath + 1f);
        renderer.draw(mc, charX, feetY, charScale, tex, AccountSkin.slim(), pose, client.capes().menuPreviewTexture());
        String name = mc.getSession().getUsername();
        int nw = fontRendererObj.getStringWidth(name);
        float tagY = feetY - pose.bob * charScale - charScale * 2.05f - 14f;
        Gui.drawRect(Math.round(charX - nw / 2f - 3), Math.round(tagY - 2), Math.round(charX + nw / 2f + 3),
                Math.round(tagY + 9), 0x59000000);
        fontRendererObj.drawString(name, Math.round(charX - nw / 2f), Math.round(tagY), 0xFFFFFFFF, false);
    }

    private String trim(String s, int w) {
        return fontRendererObj.getStringWidth(s) <= w ? s : fontRendererObj.trimStringToWidth(s, Math.max(0, w - 6)) + "...";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
