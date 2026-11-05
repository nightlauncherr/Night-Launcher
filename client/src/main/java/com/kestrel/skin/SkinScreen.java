package com.kestrel.skin;

import com.kestrel.KestrelClient;
import com.kestrel.gui.Theme;
import com.kestrel.gui.widgets.ActionButton;
import com.kestrel.menu.FullFrameRateScreen;
import com.kestrel.menu.MenuBackground;
import com.kestrel.menu.PlayerModelRenderer;
import com.kestrel.menu.VirtualScale;
import com.kestrel.util.AsyncLoader;
import com.kestrel.util.RenderUtil;
import com.kestrel.util.TextureLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The Kestrel Skin screen, opened from the SKIN button under the main menu's
 * character. Laid out like the menu: the character (the selected skin, drag
 * to turn it) large on the right, the controls on the left. Same features as the
 * launcher's Skin page and the same files, so the two stay in sync and this
 * works with the launcher closed: 3D preview, import a PNG (system file
 * picker, or the import folder), load a NameMC profile link, apply, restore
 * the account skin, classic/slim arms.
 */
public final class SkinScreen extends GuiScreen implements FullFrameRateScreen {
    /** Whole-number zoom of the virtual canvas (see VirtualScale). */
    private int zoom = 1;
    private static final String PREVIEW_TEXTURE = "kestrel_skin_preview";
    private static final int ROW_H = 16;

    private final KestrelClient client;
    private final GuiScreen parent;
    private final SkinManager skins;
    private final PlayerModelRenderer renderer = new PlayerModelRenderer();
    private final PlayerModelRenderer.Pose pose = new PlayerModelRenderer.Pose();
    private final long openedNanos = System.nanoTime();
    private final List<ActionButton> buttons = new ArrayList<ActionButton>();
    /** Files chosen in the (AWT) file picker, handed to the game thread. */
    private final ConcurrentLinkedQueue<Object> pickerResults = new ConcurrentLinkedQueue<Object>();

    private GuiTextField urlField;
    private ActionButton modelButton;
    private ActionButton applyButton;
    private ActionButton restoreButton;
    private ActionButton loadButton;
    private ActionButton importButton;

    /** Selected library file; null = the account skin. */
    private String selected;
    private boolean selectionInitialised;
    private ResourceLocation previewTexture;
    private String previewFile;
    private boolean previewLegacy;
    private String previewLoading;

    private boolean busy;
    private boolean pickerOpen;
    private String message;
    private boolean messageOk = true;
    private float yaw = -25f;
    private boolean dragging;
    private int lastDragX;
    private int listScroll;

    private int previewX, previewY, previewW, previewH, colX, colW, listY, listH, headerY, msgY;
    private float charX, feetY, charScale;

    public SkinScreen(KestrelClient client, SkinManager skins, GuiScreen parent) {
        this.client = client;
        this.skins = skins;
        this.parent = parent;
    }

    private SkinStore.State state() {
        return skins.state();
    }

    @Override
    public void initGui() {
        zoom = VirtualScale.apply(this, mc);
        Keyboard.enableRepeatEvents(true);
        if (!selectionInitialised) {
            selected = state().active;
            selectionInitialised = true;
        }
        // Left: a column of controls. Right: the character, large, as on the main menu.
        colX = Math.max(14, Math.round(width * 0.06f));
        colW = Math.min(280, Math.round(width * 0.56f) - colX);
        headerY = Math.max(10, Math.round(height * 0.07f));
        charScale = Math.max(36f, Math.min(150f, height * 0.25f));
        feetY = height * 0.80f;
        if (feetY - charScale * 2.25f < 26f) charScale = (feetY - 26f) / 2.25f;
        charX = Math.max(colX + colW + charScale * 0.9f, width * 0.72f);
        previewW = Math.round(charScale * 1.6f);
        previewX = Math.round(charX - previewW / 2f);
        previewY = Math.round(feetY - charScale * 2.3f);
        previewH = Math.round(feetY - previewY);

        buttons.clear();
        int y = headerY + 40;
        int third = (colW - 8) / 3;
        importButton = add(new ActionButton(fit("Import PNG...", "Import", third), true, this::pickFile), colX, y, third, 18);
        add(new ActionButton(fit("Open folder", "Folder", third), false, this::openFolder), colX + third + 4, y, third, 18);
        add(new ActionButton("Refresh", false, this::importFolder), colX + 2 * (third + 4), y, colW - 2 * (third + 4), 18);
        y += 34;
        String oldUrl = urlField != null ? urlField.getText() : "";
        urlField = new GuiTextField(0, fontRendererObj, colX + 1, y + 1, colW - 68, 16);
        urlField.setMaxStringLength(300);
        urlField.setText(oldUrl);
        loadButton = add(new ActionButton("Load URL", false, this::loadUrl), colX + colW - 62, y, 62, 18);
        listY = y + 30;
        int bottom = height - 34;
        int half = (colW - 4) / 2;
        applyButton = add(new ActionButton("Apply Skin", true, this::apply), colX, bottom - 24, half, 18);
        restoreButton = add(new ActionButton(fit("Restore Account Skin", "Account Skin", colW - half - 4), false, this::restore),
                colX + half + 4, bottom - 24, colW - half - 4, 18);
        add(new ActionButton("Back", false, () -> mc.displayGuiScreen(parent)), colX, bottom, 60, 18);
        listH = Math.max(ROW_H * 2, bottom - 30 - listY);
        msgY = bottom + 4;
        modelButton = add(new ActionButton("Arms", false, this::toggleModel), Math.round(charX - 55), Math.round(feetY + 10), 110, 18);
        AccountSkin.request(client);
    }

    /** The long label when it fits the button, else the short one. */
    private String fit(String full, String shorter, int buttonWidth) {
        return fontRendererObj.getStringWidth(full) + 10 <= buttonWidth ? full : shorter;
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

    // ---- actions --------------------------------------------------------

    private void save(SkinStore.State s, String what) {
        try {
            skins.saveAndApply(s);
            say(true, what);
        } catch (IOException e) {
            say(false, "Could not save: " + e.getMessage());
        }
    }

    private void apply() {
        SkinStore.State s = skins.store().load();
        if (selected == null || s.find(selected) == null) return;
        s.active = selected;
        save(s, "Skin applied. It is shown only in your own client.");
    }

    private void restore() {
        SkinStore.State s = skins.store().load();
        s.active = null;
        selected = null;
        save(s, "Account skin restored.");
    }

    private void toggleModel() {
        if (selected == null || previewLegacy) return;
        SkinStore.State s = skins.store().load();
        SkinStore.Entry e = s.find(selected);
        if (e == null) return;
        e.model = SkinStore.MODEL_SLIM.equals(e.model) ? SkinStore.MODEL_CLASSIC : SkinStore.MODEL_SLIM;
        save(s, "Arm model: " + e.model + ".");
    }

    private void remove(String file) {
        SkinStore.State s = skins.store().load();
        try {
            skins.store().remove(s, file);
        } catch (IOException e) {
            say(false, "Could not remove: " + e.getMessage());
            return;
        }
        if (file.equals(selected)) selected = s.active;
        save(s, "Skin removed.");
    }

    /** Run an import on the loader thread; on success select the new skin. */
    private void runImport(final String what, final Callable<String> task) {
        if (busy) return;
        busy = true;
        say(true, what);
        client.loader().submit(task, new AsyncLoader.Callback<String>() {
            @Override
            public void done(String file, Throwable error) {
                busy = false;
                if (error != null) {
                    String m = error instanceof SkinStore.SkinException ? error.getMessage()
                            : error.getClass().getSimpleName() + ": " + error.getMessage();
                    say(false, m);
                    return;
                }
                skins.loadNow();
                if (file != null) {
                    selected = file;
                    SkinStore.Entry e = state().find(file);
                    say(true, "Loaded \"" + (e != null ? e.name : file) + "\". Check the preview and arms, then press Apply Skin.");
                }
            }
        });
    }

    private void loadUrl() {
        final String input = urlField.getText();
        final SkinUrl.Source src;
        try {
            src = SkinUrl.parse(input); // fail fast, without network
        } catch (SkinStore.SkinException e) {
            say(false, e.getMessage());
            return;
        }
        if (src.kind == SkinUrl.Kind.NAMEMC_SKIN) {
            say(false, SkinUrl.NAMEMC_SKIN_PAGE_HELP);
            return;
        }
        runImport("Looking up the skin...", new Callable<String>() {
            @Override
            public String call() throws Exception {
                SkinUrl.Downloaded d = SkinUrl.resolve(src, SkinUrl.HTTP);
                SkinStore.State s = skins.store().load();
                SkinStore.Entry e = skins.store().importBytes(s, d.bytes, d.name, input.trim(), d.model);
                skins.store().save(s);
                return e.file;
            }
        });
    }

    private void importFile(final File f) {
        runImport("Checking " + f.getName() + "...", new Callable<String>() {
            @Override
            public String call() throws Exception {
                SkinStore.State s = skins.store().load();
                SkinStore.Entry e = skins.store().importFile(s, f);
                skins.store().save(s);
                return e.file;
            }
        });
    }

    private void importFolder() {
        if (busy) return;
        busy = true;
        client.loader().submit(new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                SkinStore.State s = skins.store().load();
                int before = s.skins.size();
                List<String> problems = skins.store().importFolder(s);
                if (s.skins.size() != before || !problems.isEmpty()) skins.store().save(s);
                return problems;
            }
        }, new AsyncLoader.Callback<List<String>>() {
            @Override
            public void done(List<String> problems, Throwable error) {
                busy = false;
                skins.loadNow();
                if (error != null) say(false, "Could not import: " + error.getMessage());
                else if (!problems.isEmpty()) say(false, problems.get(0) + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : ""));
                else say(true, "Library refreshed. PNGs in skins/import are added automatically.");
            }
        });
    }

    /** System file picker on its own thread (it blocks until closed). */
    private void pickFile() {
        if (pickerOpen || busy) return;
        if (GraphicsEnvironment.isHeadless() || Minecraft.isRunningOnMac) {
            say(false, "No file picker here: put PNGs in the import folder (Open folder), then press Refresh.");
            return;
        }
        pickerOpen = true;
        say(true, "Choose a PNG in the file picker (it may open behind the game window).");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FileDialog d = new FileDialog((Frame) null, "Choose a Minecraft skin (PNG, 64x64 or 64x32)", FileDialog.LOAD);
                    d.setFile("*.png");
                    d.setVisible(true);
                    String name = d.getFile();
                    String dir = d.getDirectory();
                    d.dispose();
                    pickerResults.add(name == null || dir == null ? Boolean.FALSE : new File(dir, name));
                } catch (Throwable e) {
                    pickerResults.add(e);
                }
            }
        }, "Kestrel-SkinPicker");
        t.setDaemon(true);
        t.start();
    }

    private void openFolder() {
        File dir = skins.store().importDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            say(false, "Cannot create " + dir);
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(dir);
            say(true, "Put skin PNGs in this folder, then press Refresh.");
        } catch (Throwable e) {
            try {
                org.lwjgl.Sys.openURL("file://" + dir.getAbsolutePath());
            } catch (Throwable ignored) {
                // nothing else to try
            }
            say(true, "Skin import folder: " + dir.getAbsolutePath());
        }
    }

    // ---- previews --------------------------------------------------------

    /** Make sure the preview texture shows the selected library skin. */
    private void updatePreview() {
        if (selected == null || selected.equals(previewFile) || selected.equals(previewLoading)) return;
        final String file = selected;
        final File path = skins.store().skinFile(file);
        if (path == null) return;
        previewLoading = file;
        client.loader().submit(new Callable<Object[]>() {
            @Override
            public Object[] call() throws Exception {
                SkinStore.Validated v = SkinStore.validate(Files.readAllBytes(path.toPath()));
                return new Object[] {SkinManager.toGameTexture(v.image), v.legacy};
            }
        }, new AsyncLoader.Callback<Object[]>() {
            @Override
            public void done(Object[] r, Throwable error) {
                if (!file.equals(previewLoading)) return;
                previewLoading = null;
                if (error != null) {
                    say(false, "Cannot preview: " + error.getMessage());
                    previewFile = file; // don't retry every frame
                    return;
                }
                if (previewTexture != null) TextureLoader.delete(previewTexture);
                previewTexture = TextureLoader.upload(PREVIEW_TEXTURE, (BufferedImage) r[0]);
                previewLegacy = (Boolean) r[1];
                previewFile = file;
            }
        });
    }

    // ---- input ------------------------------------------------------------

    @Override
    public void updateScreen() {
        urlField.updateCursorCounter();
        Object r;
        while ((r = pickerResults.poll()) != null) {
            pickerOpen = false;
            if (r instanceof File) importFile((File) r);
            else if (r instanceof Throwable) say(false, "File picker unavailable (" + r + "). Use Open folder + Refresh instead.");
            else say(true, "No file chosen.");
        }
        if (selected != null && state().find(selected) == null) selected = state().active;
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (urlField.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                loadUrl();
                return;
            }
            if (key != Keyboard.KEY_ESCAPE) {
                urlField.textboxKeyTyped(c, key);
                return;
            }
        }
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        urlField.mouseClicked(mx, my, button);
        for (ActionButton b : buttons) {
            if (b.mouseClicked(mx, my, button)) return;
        }
        if (button == 0 && mx >= previewX && mx < previewX + previewW && my >= previewY && my < previewY + previewH) {
            dragging = true;
            lastDragX = mx;
            return;
        }
        if (button == 0 && mx >= colX && mx < colX + colW && my >= listY && my < listY + listH) {
            int row = (my - listY) / ROW_H + listScroll;
            List<SkinStore.Entry> list = state().skins;
            if (row == 0) {
                selected = null;
            } else if (row - 1 < list.size()) {
                SkinStore.Entry e = list.get(row - 1);
                if (mx >= colX + colW - 16) remove(e.file);
                else selected = e.file;
            }
        }
        super.mouseClicked(mx, my, button);
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (dragging) {
            yaw += (mx - lastDragX) * 2.0f;
            lastDragX = mx;
        }
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        dragging = false;
        super.mouseReleased(mx, my, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int rows = state().skins.size() + 1;
            int visible = Math.max(1, listH / ROW_H);
            listScroll = Math.max(0, Math.min(Math.max(0, rows - visible), listScroll + (wheel > 0 ? -1 : 1)));
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (previewTexture != null) {
            TextureLoader.delete(previewTexture);
            previewTexture = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.scale((float) zoom, (float) zoom, 1f);
        try {
            drawScaled(mouseX / zoom, mouseY / zoom, partialTicks);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawScaled(int mouseX, int mouseY, float partialTicks) {
        updatePreview();
        float time = (System.nanoTime() - openedNanos) / 1.0e9f;
        MenuBackground.draw(width, height, time, 1f, charX, feetY - charScale, charScale * 1.9f);

        // Header.
        GlStateManager.pushMatrix();
        GlStateManager.translate(colX, headerY, 0f);
        GlStateManager.scale(2f, 2f, 1f);
        fontRendererObj.drawString("SKIN", 0, 0, Theme.TEXT, false);
        GlStateManager.popMatrix();
        fontRendererObj.drawString("Shown only in your own client. Your account skin is not changed.",
                colX, headerY + 20, Theme.MUTED, false);

        SkinStore.Entry active = state().activeEntry();
        String activeLine = active != null ? "Active: " + active.name + " (" + active.model + ")" : "Active: your account skin";
        fontRendererObj.drawString(trim(activeLine, colW), colX, listY - 12, Theme.OK, false);

        // Character preview (the selected skin; drag to turn it).
        SkinStore.Entry sel = state().find(selected);
        ResourceLocation tex = sel == null ? AccountSkin.texture() : (selected.equals(previewFile) ? previewTexture : null);
        boolean slim = sel == null ? AccountSkin.slim() : SkinStore.MODEL_SLIM.equals(sel.model);
        MenuBackground.shadow(charX, feetY + 1f, charScale * 0.42f, charScale * 0.09f, 0.35f);
        if (tex != null) {
            float breath = (float) Math.sin(time * 1.6f);
            pose.reset();
            pose.yaw = yaw;
            pose.rightArmZ = 0.045f + 0.025f * breath;
            pose.leftArmZ = -pose.rightArmZ;
            pose.bob = 0.006f * (breath + 1f);
            renderer.draw(mc, charX, feetY, charScale, tex, slim, pose);
        } else {
            String l = "Loading...";
            fontRendererObj.drawString(l, Math.round(charX - fontRendererObj.getStringWidth(l) / 2f), Math.round(feetY - charScale), Theme.MUTED, false);
        }
        String title = sel == null ? "Account skin" : sel.name;
        title = trim(title, Math.round(charScale * 2.4f));
        int tw = fontRendererObj.getStringWidth(title);
        int ty = Math.round(feetY - charScale * 2.05f - 14f);
        Gui.drawRect(Math.round(charX - tw / 2f - 3), ty - 2, Math.round(charX + tw / 2f + 3), ty + 9, 0x59000000);
        fontRendererObj.drawString(title, Math.round(charX - tw / 2f), ty, 0xFFFFFFFF, false);
        String hint = "drag to turn";
        fontRendererObj.drawString(hint, Math.round(charX - fontRendererObj.getStringWidth(hint) / 2f), Math.round(feetY + 32), Theme.MUTED, false);

        modelButton.enabled = sel != null && !previewLegacy && !busy;
        modelButton.setLabel(sel == null ? "Arms: " + (AccountSkin.slim() ? "slim" : "classic")
                : "Arms: " + (SkinStore.MODEL_SLIM.equals(sel.model) ? "Slim (3 px)" : "Classic (4 px)"));
        applyButton.enabled = !busy && sel != null && !selected.equals(state().active);
        restoreButton.enabled = !busy && state().active != null;
        loadButton.enabled = !busy;
        importButton.enabled = !busy && !pickerOpen;

        fontRendererObj.drawString("Load from NameMC (profile link)", colX, urlField.yPosition - 12, Theme.MUTED, false);
        if (urlField.getText().isEmpty() && !urlField.isFocused()) {
            fontRendererObj.drawString(trim("https://namemc.com/profile/<name>", urlField.getWidth() - 8), colX + 5,
                    urlField.yPosition + 4, 0xFF6A7078, false);
        }
        urlField.drawTextBox();

        // Library list.
        RenderUtil.softRect(colX, listY, colX + colW, listY + listH, 0xC0141518);
        List<SkinStore.Entry> list = state().skins;
        int visible = Math.max(1, listH / ROW_H);
        for (int i = 0; i < visible; i++) {
            int row = i + listScroll;
            if (row > list.size()) break;
            int y = listY + i * ROW_H;
            String file = row == 0 ? null : list.get(row - 1).file;
            boolean isSel = file == null ? selected == null : file.equals(selected);
            boolean isActive = file == null ? state().active == null : file.equals(state().active);
            boolean hover = mouseX >= colX && mouseX < colX + colW && mouseY >= y && mouseY < y + ROW_H;
            if (isSel || hover) RenderUtil.rect(colX, y, colX + colW, y + ROW_H, isSel ? Theme.ACCENT_DIM : Theme.CARD_HOVER);
            if (isSel) RenderUtil.rect(colX, y, colX + 2, y + ROW_H, Theme.ACCENT);
            String name = row == 0 ? "Account skin" : list.get(row - 1).name + "  (" + list.get(row - 1).model + ")";
            fontRendererObj.drawString(trim(name, colW - 70), colX + 6, y + 4, Theme.TEXT, false);
            if (isActive) fontRendererObj.drawString("active", colX + colW - 56, y + 4, Theme.OK, false);
            if (row > 0 && hover) {
                boolean xHover = mouseX >= colX + colW - 16;
                fontRendererObj.drawString("x", colX + colW - 10, y + 4, xHover ? Theme.ERR : Theme.MUTED, false);
            }
        }
        if (list.isEmpty()) {
            fontRendererObj.drawString("Import a PNG or load a NameMC profile link.", colX + 6, listY + ROW_H + 4, Theme.MUTED, false);
        }

        for (ActionButton b : buttons) b.draw(mc, mouseX, mouseY);

        if (message != null) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth(message, Math.max(80, colW - 66));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                fontRendererObj.drawString(lines.get(i), colX + 66, msgY + i * 10 - 4, messageOk ? Theme.MUTED : Theme.ERR, false);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String trim(String s, int w) {
        return fontRendererObj.getStringWidth(s) <= w ? s : fontRendererObj.trimStringToWidth(s, Math.max(0, w - 6)) + "...";
    }
}
