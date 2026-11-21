package com.kestrel.gui;

import com.kestrel.KestrelClient;
import com.kestrel.animation.FirstPersonAnimator;
import com.kestrel.animation.SwingStyle;
import com.kestrel.config.ClientConfig;
import com.kestrel.config.Preset;
import com.kestrel.cosmetics.CapeManager;
import com.kestrel.cosmetics.MojangCapes;
import com.kestrel.effects.KillEffectType;
import com.kestrel.gui.settings.Anim;
import com.kestrel.gui.settings.ButtonRow;
import com.kestrel.gui.settings.ColorRow;
import com.kestrel.gui.settings.DropdownRow;
import com.kestrel.gui.settings.Icons;
import com.kestrel.gui.settings.KeybindRow;
import com.kestrel.gui.settings.NoteRow;
import com.kestrel.gui.settings.Popup;
import com.kestrel.gui.settings.Row;
import com.kestrel.gui.settings.SectionHeader;
import com.kestrel.gui.settings.SettingsSearch;
import com.kestrel.gui.settings.SliderRow;
import com.kestrel.gui.settings.SubTabs;
import com.kestrel.gui.settings.ToggleRow;
import com.kestrel.gui.settings.Ui;
import com.kestrel.gui.widgets.Preview;
import com.kestrel.gui.widgets.TileGrid;
import com.kestrel.gui.widgets.Widget;
import com.kestrel.hud.CrosshairRenderer;
import com.kestrel.hud.HudElement;
import com.kestrel.input.InputManager;
import com.kestrel.performance.FrameStats;
import com.kestrel.rendering.SkyCatalog;
import com.kestrel.rendering.SkyManager;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-game settings (Right Shift by default). Every control is bound to a
 * real config value; changes apply immediately and are saved automatically.
 *
 * <p>Layout: a category sidebar, a top bar with the page title and the
 * search box, and the page itself as a column of section cards. Each card
 * starts at a {@link SectionHeader} and holds the rows after it.
 */
public final class ClientSettingsScreen extends GuiScreen {
    private enum Category {
        COMBAT("Combat", Icons.COMBAT), MOVEMENT("Movement", Icons.MOVEMENT), VISUALS("Visuals", Icons.VISUALS),
        HUD("HUD", Icons.HUD), WORLD("World", Icons.WORLD), COSMETICS("Cosmetics", Icons.COSMETICS),
        PERFORMANCE("Performance", Icons.PERFORMANCE), OTHER("Other", Icons.OTHER);

        final String label;
        final String[] icon;

        Category(String label, String[] icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private static final List<String> COSMETIC_TABS = Arrays.asList("Capes", "Kill effects", "Sky", "Animations");
    private static final int TAB_CAPES = 0;
    private static final int TAB_EFFECTS = 1;
    private static final int TAB_SKY = 2;
    private static final int TAB_ANIMATIONS = 3;

    private static Category lastCategory = Category.COMBAT;
    private static int lastCosmeticsTab = TAB_CAPES;
    private static final TileGrid.Filter CAPE_FILTER = new TileGrid.Filter();
    private static final TileGrid.Filter SKY_FILTER = new TileGrid.Filter();
    private static final TileGrid.Filter EFFECT_FILTER = new TileGrid.Filter();
    private static final String VANILLA_SKY = "__vanilla";
    /** Texture size of each thumbnail (queried once from GL). */
    private static final Map<ResourceLocation, int[]> THUMB_SIZES = new HashMap<ResourceLocation, int[]>();

    private static final long OPEN_MS = 160;
    private static final long CLOSE_MS = 110;
    private static final long SWITCH_MS = 160;
    private static final int TOP_H = 30;
    private static final int CARD_GAP = 8;
    private static final int SEARCH_H = 16;
    private static final int MAX_RESULTS = 80;
    private static final int MAX_QUERY = 40;

    /** A tile grid seen while building, so search can find capes, skies and effects by name. */
    private static final class GridRef {
        final List<TileGrid.Item> items;
        final TileGrid.Filter filter;
        final String kind;

        GridRef(List<TileGrid.Item> items, TileGrid.Filter filter, String kind) {
            this.items = items;
            this.filter = filter;
            this.kind = kind;
        }
    }

    /** One search hit, kept with its place so results can be grouped and linked. */
    private static final class Hit {
        final Widget widget;
        final int score;
        final int order;

        Hit(Widget widget, int score, int order) {
            this.widget = widget;
            this.score = score;
            this.order = order;
        }
    }

    private static final class Group {
        final String title;
        final Category category;
        final int tab;
        final String section;
        final List<Hit> hits = new ArrayList<Hit>();
        int best;

        Group(String title, Category category, int tab, String section) {
            this.title = title;
            this.category = category;
            this.tab = tab;
            this.section = section;
        }
    }

    private final KestrelClient client;
    private final GuiScreen parent;

    // ---- page content (rebuilt on navigation and on master toggles)
    private final List<Widget> content = new ArrayList<Widget>();
    /** Section title of each entry in {@link #content}, while building. */
    private final List<String> sections = new ArrayList<String>();
    private final List<GridRef> grids = new ArrayList<GridRef>();
    private String section = "";
    private int[] relY = new int[0];
    private int[] cardTop = new int[0];
    private int[] cardBottom = new int[0];
    private int[] cardFirst = new int[0];
    private int cardCount;
    private SubTabs subTabs;
    private String title = "";
    private String presetLabel = "";

    // ---- navigation
    private Category category = lastCategory;
    private int cosmeticsTab = lastCosmeticsTab;
    private String query = "";
    private boolean searchFocused;
    private String pendingJump;

    // ---- geometry
    private int px, py, pw, ph, sideW, entryStep;
    private int contentX, contentY, contentW, contentH, innerW;
    private int searchX, searchY, searchW, closeX, closeY;
    private boolean narrow;

    // ---- interaction and animation
    private float scrollTarget;
    private float scrollShown;
    private int contentHeight;
    private Widget dragging;
    private boolean dragPopup;
    private boolean dragScrollbar;
    private int dragGrab;
    private int barY;
    private int barH;
    private int scaleFactor = 1;
    private long lastFrame;
    private long openStart;
    private long closeStart;
    private long switchStart;
    private int switchDir;
    private final float[] sideHover = new float[Category.values().length];
    private float sideSel = -1;
    private float closeHover;
    private float backHover;
    private float searchHover;
    private KeyBinding openKey;

    public ClientSettingsScreen(KestrelClient client) {
        this(client, null);
    }

    /** @param parent screen to return to on Esc (null = back to the game) */
    public ClientSettingsScreen(KestrelClient client, GuiScreen parent) {
        this.client = client;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        client.capes().reload(); // pick up newly added custom files
        client.sky().reload();
        Keyboard.enableRepeatEvents(true);
        Ui.closePopup();
        if (openStart == 0) openStart = System.currentTimeMillis();
        openKey = null;
        for (KeyBinding kb : mc.gameSettings.keyBindings) {
            if ("Open Kestrel menu".equals(kb.getKeyDescription())) openKey = kb;
        }
        scaleFactor = new ScaledResolution(mc).getScaleFactor();
        pw = Math.min(width - 20, 560);
        ph = Math.min(height - 20, 360);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        narrow = pw < 420;
        sideW = narrow ? 30 : 108;
        contentX = px + sideW + 10;
        contentW = pw - sideW - 20;
        innerW = contentW - 7; // room for the scrollbar
        closeX = px + pw - 22;
        closeY = py + 9;
        searchW = Math.max(70, Math.min(160, contentW / 2));
        searchX = closeX - 6 - searchW;
        searchY = py + 7;
        entryStep = Math.max(14, Math.min(22, (ph - 40 - (narrow ? 8 : 22)) / Category.values().length));
        rebuild();
    }

    private ClientConfig cfg() {
        return client.config();
    }

    private void changed() {
        client.onConfigChanged();
    }

    private boolean searching() {
        return !query.trim().isEmpty();
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    private void select(Category c, int tab) {
        int from = searching() ? -1 : category.ordinal() * 10 + cosmeticsTab;
        int to = c.ordinal() * 10 + tab;
        query = "";
        searchFocused = false;
        if (from == to) {
            rebuild();
            return;
        }
        switchDir = to > from ? 1 : -1;
        switchStart = System.currentTimeMillis();
        category = c;
        lastCategory = c;
        if (c == Category.COSMETICS) {
            cosmeticsTab = tab;
            lastCosmeticsTab = tab;
        }
        scrollTarget = 0;
        scrollShown = 0;
        rebuild();
    }

    /** Go to a page and scroll to one of its sections (from a search result). */
    private void jump(Category c, int tab, String sectionTitle) {
        pendingJump = sectionTitle;
        select(c, tab);
        if (pendingJump != null) {
            for (int i = 0; i < content.size(); i++) {
                Widget w = content.get(i);
                if (w instanceof SectionHeader && ((SectionHeader) w).title.equals(pendingJump)) {
                    scrollTarget = Math.max(0, Math.min(relY[i], maxScroll()));
                    break;
                }
            }
            pendingJump = null;
        }
    }

    private void setQuery(String q) {
        boolean was = searching();
        query = q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q;
        if (was != searching()) {
            switchStart = System.currentTimeMillis();
            switchDir = searching() ? 1 : -1;
        }
        scrollTarget = 0;
        scrollShown = 0;
        rebuild();
    }

    /** Rebuild the visible page (or the search results) and lay it out. */
    private void rebuild() {
        Ui.closePopup();
        dragging = null;
        if (searching()) {
            buildSearch();
            subTabs = null;
            title = "Search";
        } else {
            clearContent();
            buildPage(category, cosmeticsTab);
            subTabs = category == Category.COSMETICS
                    ? new SubTabs(COSMETIC_TABS, cosmeticsTab, i -> select(Category.COSMETICS, i)) : null;
            title = category.label;
        }
        Preset p = Preset.byId(cfg().activePreset);
        presetLabel = p != null ? p.label : "-";
        contentY = py + TOP_H + (subTabs != null ? 26 : 0);
        contentH = py + ph - 8 - contentY;
        if (subTabs != null) {
            subTabs.x = contentX;
            subTabs.y = py + TOP_H;
            subTabs.w = innerW;
        }
        layout();
    }

    private void clearContent() {
        content.clear();
        sections.clear();
        grids.clear();
        section = "";
    }

    // =====================================================================
    // Search
    // =====================================================================

    private static String place(Category c, int tab) {
        return c == Category.COSMETICS ? c.label + " › " + COSMETIC_TABS.get(tab) : c.label;
    }

    private void buildSearch() {
        String q = query.trim();
        Map<String, Group> groups = new LinkedHashMap<String, Group>();
        int order = 0;
        for (Category c : Category.values()) {
            int tabs = c == Category.COSMETICS ? COSMETIC_TABS.size() : 1;
            for (int tab = 0; tab < tabs; tab++) {
                clearContent();
                buildPage(c, tab);
                String where = place(c, tab);
                for (int i = 0; i < content.size(); i++) {
                    Widget w = content.get(i);
                    if (!(w instanceof Row)) continue;
                    Row r = (Row) w;
                    String sec = sections.get(i);
                    int s = SettingsSearch.score(q, r.label, r.searchDescription(), sec, where);
                    if (s <= 0) continue;
                    String key = where + " › " + sec;
                    Group g = groups.get(key);
                    if (g == null) {
                        g = new Group(sec.isEmpty() ? where : key, c, tab, sec);
                        groups.put(key, g);
                    }
                    g.hits.add(new Hit(r, s, order++));
                    g.best = Math.max(g.best, s);
                }
                // Capes, skies and kill effects by name: a link that opens the grid filtered to it.
                for (GridRef ref : grids) {
                    String key = where + " › " + ref.kind;
                    for (final TileGrid.Item it : ref.items) {
                        int s = SettingsSearch.score(q, it.name, "", ref.kind, "");
                        if (s <= 0) continue;
                        Group g = groups.get(key);
                        if (g == null) {
                            g = new Group(key, c, tab, ref.kind);
                            groups.put(key, g);
                        }
                        if (g.hits.size() >= 12) break;
                        final TileGrid.Filter filter = ref.filter;
                        final Category cat = c;
                        final int t = tab;
                        ButtonRow link = new ButtonRow(it.name, null, false, () -> {
                            filter.category = "";
                            filter.search = it.name.length() > 32 ? it.name.substring(0, 32) : it.name;
                            select(cat, t);
                        });
                        link.describe("Show in " + where);
                        g.hits.add(new Hit(link, s, order++));
                        g.best = Math.max(g.best, s);
                    }
                }
            }
        }
        clearContent();
        List<Group> sorted = new ArrayList<Group>(groups.values());
        // Stable: equal scores keep page order.
        sorted.sort((a, b) -> Integer.compare(b.best, a.best));
        int shown = 0;
        for (final Group g : sorted) {
            if (shown >= MAX_RESULTS) break;
            final Category gc = g.category;
            final int gt = g.tab;
            final String gs = g.section;
            add(new SectionHeader(g.title, () -> jump(gc, gt, gs)));
            for (Hit h : g.hits) {
                if (shown >= MAX_RESULTS) break;
                add(h.widget);
                shown++;
            }
        }
        if (content.isEmpty()) {
            add(new SectionHeader("No results", null));
            add(new NoteRow("Nothing matches \"" + q + "\". Try a shorter word, such as \"zoom\" or \"sky\".", Theme.MUTED));
        }
    }

    // =====================================================================
    // Building blocks
    // =====================================================================

    private void add(Widget w) {
        content.add(w);
        sections.add(section);
    }

    private void header(String s) {
        section = s;
        add(new SectionHeader(s, null));
    }

    /** Explanation: under the previous setting when there is one, otherwise a note in the card. */
    private void text(String s) {
        Widget last = content.isEmpty() ? null : content.get(content.size() - 1);
        if (last instanceof Row) {
            ((Row) last).describe(s);
        } else {
            note(s);
        }
    }

    private void note(String s) {
        add(new NoteRow(s, Theme.MUTED));
    }

    private void warn(String s) {
        add(new NoteRow(s, Theme.ERR));
    }

    private ToggleRow toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        ToggleRow r = new ToggleRow(label, get, v -> {
            set.accept(v);
            changed();
        });
        add(r);
        return r;
    }

    /** Toggle that also rebuilds the page, so dependent settings appear or disappear. */
    private ToggleRow master(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        ToggleRow r = new ToggleRow(label, get, v -> {
            set.accept(v);
            changed();
            rebuild();
        });
        add(r);
        return r;
    }

    private ColorRow color(String label, Supplier<Integer> get, Consumer<Integer> set) {
        ColorRow r = new ColorRow(label, get, v -> {
            set.accept(v);
            changed();
        });
        add(r);
        return r;
    }

    private DropdownRow dropdown(String label, List<String> ids, List<String> names, Supplier<String> get, Consumer<String> set) {
        DropdownRow r = new DropdownRow(label, ids, names, get, v -> {
            set.accept(v);
            changed();
        });
        add(r);
        return r;
    }

    private ButtonRow button(String label, String buttonText, boolean primary, Runnable action) {
        ButtonRow r = new ButtonRow(label, buttonText, primary, action);
        add(r);
        return r;
    }

    private interface IntGet {
        int get();
    }

    private interface IntSet {
        void set(int v);
    }

    private SliderRow intSlider(String label, int min, int max, IntGet get, IntSet set) {
        SliderRow r = new SliderRow(label, min, max, 1, () -> (float) get.get(), v -> {
            set.set(Math.round(v));
            changed();
        }, v -> String.valueOf(Math.round(v)));
        add(r);
        return r;
    }

    private interface FloatGet {
        float get();
    }

    private interface FloatSet {
        void set(float v);
    }

    private SliderRow floatSlider(String label, float min, float max, float step, FloatGet get, FloatSet set, String fmt) {
        SliderRow r = new SliderRow(label, min, max, step, get::get, v -> {
            set.set(v);
            changed();
        }, v -> String.format(fmt, v));
        add(r);
        return r;
    }

    private void grid(TileGrid g, List<TileGrid.Item> items, TileGrid.Filter filter, String kind) {
        add(g);
        grids.add(new GridRef(items, filter, kind));
    }

    private static List<String> list(String... s) {
        List<String> l = new ArrayList<String>(s.length);
        for (String x : s) l.add(x);
        return l;
    }

    /** Pairs (id, label, id, label, ...) as category rows for a {@link TileGrid}. */
    private static List<String[]> list2(String... s) {
        List<String[]> l = new ArrayList<String[]>(s.length / 2);
        for (int i = 0; i + 1 < s.length; i += 2) l.add(new String[] {s[i], s[i + 1]});
        return l;
    }

    private static String[] capeCatOf(List<String[]> cats, String id) {
        for (String[] c : cats) if (c[0].equals(id)) return c;
        return null;
    }

    private HudElement hudElement(String id) {
        for (HudElement e : client.hud().elements()) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    private boolean allHud(boolean background) {
        for (HudElement e : client.hud().elements()) {
            if (background ? !e.cfg().background : !e.cfg().textShadow) return false;
        }
        return true;
    }

    // =====================================================================
    // Pages
    // =====================================================================

    private void buildPage(Category c, int tab) {
        ClientConfig cf = cfg();
        switch (c) {
            case COMBAT:
                buildCombat(cf.qol, cf.visuals);
                break;
            case MOVEMENT:
                buildMovement(cf);
                break;
            case VISUALS:
                buildVisuals(cf);
                break;
            case HUD:
                buildHud(cf.qol.blockCounter);
                break;
            case WORLD:
                buildWorld(cf);
                break;
            case COSMETICS:
                if (tab == TAB_EFFECTS) buildEffects(cf.killEffects);
                else if (tab == TAB_SKY) buildSky(cf.sky);
                else if (tab == TAB_ANIMATIONS) buildAnimations(cf.animations);
                else buildCapes(cf.cosmetics);
                break;
            case PERFORMANCE:
                buildPerformance(cf);
                break;
            case OTHER:
                buildOther(cf);
                break;
            default:
                break;
        }
    }

    private void buildCombat(final ClientConfig.Qol q, final ClientConfig.Visuals vs) {
        header("Hit Color");
        final ClientConfig.HitColor hc = q.hitColor;
        master("Hit Color", () -> hc.enabled, v -> hc.enabled = v);
        text("Tints a player you hit, only after the server confirms the hit (not misses or other players' hits), then fades back. Skin and armour, rendering only.");
        if (hc.enabled) {
            color("Hit colour", () -> hc.color, v -> hc.color = v);
            floatSlider("Duration", 0.1f, 2.0f, 0.05f, () -> hc.duration, v -> hc.duration = v, "%.2f s");
            floatSlider("Intensity", 0.1f, 0.9f, 0.05f, () -> hc.intensity, v -> hc.intensity = v, "%.2f");
            toggle("Fade out", () -> hc.fade, v -> hc.fade = v);
        }

        header("Hit Delay Fix");
        final ClientConfig.HitDelayFix hd = q.hitDelayFix;
        master("Hit Delay Fix", () -> hd.enabled, v -> hd.enabled = v);
        text("Client-side responsiveness only. Attack cooldown, reach, packets and every server rule are unchanged.");
        if (hd.enabled) {
            toggle("Remove 0.5 s lock after missed clicks", () -> hd.removeMissLock, v -> hd.removeMissLock = v);
            text("Vanilla ignores clicks for half a second after clicking air. Hits on players never had that lock, so no attacks are added.");
            toggle("Restart swing animation on every click", () -> hd.instantSwing, v -> hd.instantSwing = v);
            text("Vanilla only restarts the swing once it is half done.");
        }

        header("Low Health Alert");
        final ClientConfig.LowHealthAlert la = q.lowHealthAlert;
        master("Low Health Alert", () -> la.enabled, v -> la.enabled = v);
        text("Alerts once when the player you are fighting drops to the threshold; resets when they recover or you change target. Needs real health from the server.");
        if (la.enabled) {
            floatSlider("Threshold", 1f, 19f, 0.5f, () -> la.threshold, v -> la.threshold = v, "%.1f HP");
            color("Alert colour", () -> la.color, v -> la.color = v);
            toggle("Sound", () -> la.sound, v -> la.sound = v);
            toggle("Show target health", () -> la.showHealth, v -> la.showHealth = v);
        }

        header("Opponent Health Prediction");
        final ClientConfig.HealthPrediction hp = q.healthPrediction;
        master("Opponent Health Prediction", () -> hp.enabled, v -> hp.enabled = v);
        text("Shows your target's health: exact when the server sends it, otherwise \"~\" estimated from your confirmed hits (armour, enchantments, blocking, crits, absorption).");
        if (hp.enabled) {
            floatSlider("Start HP", 1f, 40f, 0.5f, () -> hp.startingHealth, v -> hp.startingHealth = v, "%.1f HP");
            text("The estimate starts here and is replaced by the exact value once the server shows real health.");
            color("Estimate colour", () -> hp.estimateColor, v -> hp.estimateColor = v);
            note("Regeneration, Resistance and server-side damage changes can't be seen, so estimates drift; exact values are never invented.");
        }

        header("Combat visuals");
        toggle("Hit sparks (gold on critical hits)", () -> vs.hitSparks, v -> vs.hitSparks = v);
        text("Shown only on your own hits.");
        toggle("Projectile trails", () -> vs.arrowTrails, v -> vs.arrowTrails = v);
        text("Follow arrows, ender pearls, snowballs, eggs and potions.");
        toggle("Arrow impact bursts", () -> vs.arrowImpacts, v -> vs.arrowImpacts = v);
    }

    private void buildMovement(final ClientConfig c) {
        header("Sprint");
        toggle("Toggle sprint", () -> c.sprint.toggleSprint, v -> c.sprint.toggleSprint = v);
        text("Press your sprint key once to keep sprinting. Vanilla rules still apply (hunger, blindness, using items).");

        header("Zoom");
        note("Hold the zoom key (C by default) to zoom. Change it under Other › Keybinds.");
        floatSlider("Zoom strength", 1.5f, 10f, 0.5f, () -> c.zoom.factor, v -> c.zoom.factor = v, "%.1fx");
        toggle("Smooth zoom", () -> c.zoom.smooth, v -> c.zoom.smooth = v);
        SliderRow speed = new SliderRow("Zoom speed", 2f, 40f, 1f, () -> c.zoom.speed, v -> {
            c.zoom.speed = v;
            changed();
        }, v -> String.valueOf(Math.round(v)));
        add(speed);
        toggle("Lower sensitivity while zoomed", () -> c.zoom.scaleSensitivity, v -> c.zoom.scaleSensitivity = v);

        header("Free look");
        note("Hold the free look key (Left Alt by default) to look around without turning.");
        toggle("Free look in third person", () -> c.freeLook.thirdPerson, v -> c.freeLook.thirdPerson = v);
        toggle("Smooth free-look return", () -> c.freeLook.smoothReturn, v -> c.freeLook.smoothReturn = v);
        note("Zoom and free look are camera-only: your FOV setting, movement and what the server sees are unchanged.");
    }

    private void buildVisuals(final ClientConfig c) {
        header("Crosshair");
        final ClientConfig.Crosshair ch = c.crosshair;
        add(new Preview(48, (mc, cx, cy, bw, bh) -> {
            if (!ch.custom) {
                String s = "Vanilla crosshair";
                mc.fontRendererObj.drawString(s, cx - mc.fontRendererObj.getStringWidth(s) / 2, cy - 4, Theme.MUTED);
                return;
            }
            float sf = new ScaledResolution(mc).getScaleFactor();
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx, cy, 0);
            GlStateManager.scale(1f / sf, 1f / sf, 1f);
            CrosshairRenderer.drawAtOrigin(ch);
            GlStateManager.popMatrix();
        }));
        toggle("Custom crosshair", () -> ch.custom, v -> ch.custom = v);
        dropdown("Style", list("0", "1", "2", "3"), list("Cross", "Dot", "Cross + dot", "T"),
                () -> String.valueOf(ch.style), v -> ch.style = Integer.parseInt(v));
        intSlider("Length", 1, 20, () -> ch.length, v -> ch.length = v);
        intSlider("Thickness", 1, 8, () -> ch.thickness, v -> ch.thickness = v);
        intSlider("Gap", 0, 15, () -> ch.gap, v -> ch.gap = v);
        color("Colour", () -> ch.color, v -> ch.color = v);
        toggle("Outline", () -> ch.outline, v -> ch.outline = v);
        color("Outline colour", () -> ch.outlineColor, v -> ch.outlineColor = v);
        toggle("Hide in third person", () -> ch.hideInThirdPerson, v -> ch.hideInThirdPerson = v);

        header("Clear Water");
        final ClientConfig.ClearWater cw = c.qol.clearWater;
        master("Clear Water", () -> cw.enabled, v -> cw.enabled = v);
        text("Thinner underwater fog so you can see further. Water blocks, collision and swimming are unchanged.");
        if (cw.enabled) {
            floatSlider("Clarity", 0f, 0.95f, 0.05f, () -> cw.clarity, v -> cw.clarity = v, "%.2f");
            floatSlider("Brightness", 0f, 1f, 0.05f, () -> cw.brightness, v -> cw.brightness = v, "%.2f");
            floatSlider("Overlay fade", 0f, 1f, 0.05f, () -> cw.overlayTransparency, v -> cw.overlayTransparency = v, "%.2f");
            text("Makes the first-person underwater texture more transparent.");
        }

        header("No Blindness");
        final ClientConfig.NoBlindness nb = c.qol.noBlindness;
        master("No Blindness", () -> nb.enabled, v -> nb.enabled = v);
        text("Removes blindness's dark fog from your screen only. The effect itself stays (you still can't sprint). Some servers, such as Hypixel, don't allow this; check the rules first.");

        header("Experience orbs");
        toggle("Experience orb glow", () -> c.visuals.xpOrbGlow, v -> c.visuals.xpOrbGlow = v);
    }

    private void buildWorld(final ClientConfig c) {
        header("Bridge Assist");
        final ClientConfig.BridgeAssist ba = c.qol.bridgeAssist;
        master("Bridge Assist", () -> ba.enabled, v -> ba.enabled = v);
        text("While holding a block, shows where it would go, the face you aim at, and how close you stand to the edge. Visual only: it never moves, aims, selects or places.");
        if (ba.enabled) {
            color("Indicator colour", () -> ba.color, v -> ba.color = v);
            floatSlider("Opacity", 0.1f, 1.0f, 0.05f, () -> ba.opacity, v -> ba.opacity = v, "%.2f");
            floatSlider("Line size", 1f, 5f, 0.5f, () -> ba.size, v -> ba.size = v, "%.1f");
            toggle("Placement preview", () -> ba.placementPreview, v -> ba.placementPreview = v);
            toggle("Edge alignment guide", () -> ba.alignmentGuide, v -> ba.alignmentGuide = v);
            text("Brightens when you are close enough to the edge; red means vanilla would refuse the placement there.");
        }

        header("Weather");
        final ClientConfig.Weather we = c.qol.weather;
        master("Weather Customizer", () -> we.enabled, v -> we.enabled = v);
        text("Changes only how weather looks and sounds for you. Server weather and time are untouched.");
        if (we.enabled) {
            toggle("Rain", () -> we.rain, v -> we.rain = v);
            toggle("Snow", () -> we.snow, v -> we.snow = v);
            toggle("Splash particles", () -> we.particles, v -> we.particles = v);
            toggle("Rain sounds", () -> we.sounds, v -> we.sounds = v);
            floatSlider("Intensity", 0.1f, 1f, 0.05f, () -> we.intensity, v -> we.intensity = v, "%.2f");
            toggle("Clear-weather look", () -> we.clearSky, v -> we.clearSky = v);
            text("Makes the sky, light and fog look clear while it rains or storms.");
        }
        toggle("Refined rain and snow", () -> c.visuals.refinedWeather, v -> c.visuals.refinedWeather = v);
        text("Swaps only the rain and snow textures; how much falls and where is unchanged.");

        header("Sky");
        button("Custom skies", "Open", false, () -> select(Category.COSMETICS, TAB_SKY))
                .describe("Skyboxes, brightness and rotation are under Cosmetics › Sky.");
    }

    private void buildHud(final ClientConfig.BlockCounter bc) {
        header("Layout");
        button("HUD editor", "Open", true, client::openHudEditor).describe("Drag modules to move them, scroll to scale.");
        button("Reset all positions", "Reset", false, () -> {
            for (HudElement e : client.hud().elements()) e.resetPosition();
            changed();
        });

        final HudElement counter = hudElement("blockcounter");
        header("Modules");
        for (final HudElement e : client.hud().elements()) {
            if (e == counter) continue; // has its own card below
            toggle(e.name, e::isEnabled, e::setEnabled);
        }

        header("Appearance");
        toggle("Backgrounds", () -> allHud(true), v -> {
            for (HudElement e : client.hud().elements()) e.cfg().background = v;
        }).describe("For all modules.");
        toggle("Text shadow", () -> allHud(false), v -> {
            for (HudElement e : client.hud().elements()) e.cfg().textShadow = v;
        });
        color("Text colour", () -> client.hud().elements().get(0).cfg().textColor, v -> {
            for (HudElement e : client.hud().elements()) e.cfg().textColor = v;
        });

        if (counter != null) {
            header("Block Counter");
            master("Block Counter", counter::isEnabled, counter::setEnabled);
            text("Shows placeable blocks in your hotbar, e.g. BLOCKS: 112. Move and scale it in the HUD editor.");
            if (counter.isEnabled()) {
                final ClientConfig.HudElementConfig ec = counter.cfg();
                color("Text colour", () -> ec.textColor, v -> ec.textColor = v);
                toggle("Background", () -> ec.background, v -> ec.background = v);
                floatSlider("Scale", 0.5f, 3.0f, 0.1f, () -> ec.scale, v -> ec.scale = v, "%.1fx");
                toggle("Block icon", () -> bc.icon, v -> bc.icon = v);
                toggle("Show held stack separately", () -> bc.showHeld, v -> bc.showHeld = v);
                master("Count only selected block types", () -> bc.onlySelected, v -> bc.onlySelected = v);
                if (bc.onlySelected) {
                    toggle("Wool", () -> bc.wool, v -> bc.wool = v);
                    toggle("Clay and terracotta", () -> bc.clay, v -> bc.clay = v);
                    toggle("Wood (planks, logs)", () -> bc.wood, v -> bc.wood = v);
                    toggle("Stone, sandstone, end stone", () -> bc.stone, v -> bc.stone = v);
                    toggle("Glass", () -> bc.glass, v -> bc.glass = v);
                    toggle("Other full blocks", () -> bc.other, v -> bc.other = v);
                }
                button("Position in HUD editor", "Open", false, client::openHudEditor);
            }
        }
    }

    private void buildCapes(final ClientConfig.Cosmetics co) {
        header("Your cape");
        add(new Preview(64, (mc, cx, cy, bw, bh) -> drawCapePreview(mc, cx, cy)));
        toggle("Show cape", () -> co.capesEnabled, v -> co.capesEnabled = v);
        if (!Minecraft.getMinecraft().gameSettings.getModelParts().contains(EnumPlayerModelParts.CAPE)) {
            // Vanilla's own switch hides every cape, Night's included.
            warn("Cape is off in Options > Skin Customization, so no cape is shown.");
        }

        header("Capes");
        final Map<String, CapeManager.Option> capeOpts = new HashMap<String, CapeManager.Option>();
        List<TileGrid.Item> capeItems = new ArrayList<TileGrid.Item>();
        for (CapeManager.Option o : client.capes().options()) {
            capeOpts.put(o.id, o);
            capeItems.add(new TileGrid.Item(o.id, o.name, o.category, o.animated ? "ANIM" : null));
        }
        // Chips only for categories this account has something in, so
        // exclusive collections leave no trace when not unlocked.
        List<String[]> capeCats = new ArrayList<String[]>();
        for (String[] cat : list2("static", "Static", "animated", "Animated", "special", "Special",
                "kestrel", "Night", MojangCapes.CATEGORY, "Mojang", "custom", "Custom")) {
            for (TileGrid.Item it : capeItems) {
                if (cat[0].equals(it.category)) {
                    capeCats.add(cat);
                    break;
                }
            }
        }
        boolean filterShown = CAPE_FILTER.category.isEmpty();
        for (String[] cat : capeCats) filterShown |= cat[0].equals(CAPE_FILTER.category);
        if (!filterShown) CAPE_FILTER.category = "";
        final CapeManager capes = client.capes();
        grid(new TileGrid(capeItems, capeCats,
                () -> capes.visible(co.cape) ? co.cape : CapeManager.NONE, v -> {
            co.cape = v;
            if (!CapeManager.NONE.equals(v)) co.capesEnabled = true;
            changed();
        }, (mc2, it, x, y, w, h) -> drawCapeThumb(mc2, capeOpts.get(it.id), x, y, w, h), CAPE_FILTER, 40, 64),
                capeItems, CAPE_FILTER, "Capes");
        if (capeCatOf(capeCats, MojangCapes.CATEGORY) != null) {
            note("Mojang: the official Minecraft capes, shown by Night (other Night players see the one you equip). "
                    + "They do not grant or change any account cape. The original textures are downloaded from "
                    + "Mojang's texture server the first time and kept on your PC.");
        }

        header("Animation");
        toggle("Animate capes", () -> co.animatedCapes, v -> co.animatedCapes = v);
        intSlider("Animation FPS (PNG strips)", 1, 30, () -> co.animationFps, v -> co.animationFps = v);

        header("Sharing");
        toggle("Show other Night players' capes", () -> co.showAssignedCapes, v -> co.showAssignedCapes = v);
        toggle("Share my cape with Night players", () -> co.shareCape, v -> co.shareCape = v);
        text(client.capes().sync().enabled()
                ? "Other Night players see your equipped built-in cape (uploaded capes stay on your PC). "
                        + "Your account is verified with the player certificate Mojang issues to it; "
                        + "your login token and keys are never sent to Night."
                : "Cape sharing is not set up in this build: capes are shown on your PC only.");

        header("Custom capes");
        button("Open custom capes folder", "Open", false, () -> openFolder(client.capes().customDir()));
        note("PNG, 64x32 (or HD multiples like 128x64), max 2 MB; animated PNGs stack frames vertically. "
                + "GIFs (max 4 MB) play with their own timing: a 2:1 GIF is used as a cape sheet, any other GIF is fitted onto both sides. "
                + "Your cape is drawn by your client only. While equipped it replaces your official Minecraft cape "
                + "on your own player; choose None to show the official one.");
        if (client.capes().lastError() != null) warn("Last error: " + client.capes().lastError());
    }

    private void buildSky(final ClientConfig.Sky sk) {
        header("Sky");
        toggle("Custom sky", () -> sk.enabled, v -> sk.enabled = v);
        text("Overworld only. Replaces the sun, moon and stars while enabled.");
        final Map<String, SkyManager.Option> skyOpts = new HashMap<String, SkyManager.Option>();
        List<TileGrid.Item> skyItems = new ArrayList<TileGrid.Item>();
        skyItems.add(new TileGrid.Item(VANILLA_SKY, "Vanilla sky", "", null));
        List<String[]> skyCats = new ArrayList<String[]>();
        for (String cat : SkyCatalog.CATEGORIES) skyCats.add(new String[] {cat, SkyCatalog.categoryLabel(cat)});
        skyCats.add(new String[] {"custom", "Custom"});
        for (SkyManager.Option o : client.sky().options()) {
            skyOpts.put(o.id, o);
            skyItems.add(new TileGrid.Item(o.id, o.name, o.category, null));
        }
        grid(new TileGrid(skyItems, skyCats, () -> sk.enabled ? sk.preset : VANILLA_SKY, v -> {
            if (VANILLA_SKY.equals(v)) {
                sk.enabled = false;
            } else {
                sk.enabled = true;
                sk.preset = v;
            }
            changed();
        }, (mc2, it, x, y, w, h) -> drawSkyThumb(mc2, skyOpts.get(it.id), it, x, y, w, h), SKY_FILTER, 96, 32),
                skyItems, SKY_FILTER, "Skies");

        header("Sky settings");
        floatSlider("Brightness", 0.1f, 1f, 0.05f, () -> sk.brightness, v -> sk.brightness = v, "%.2f");
        toggle("Slow rotation (animated)", () -> sk.rotate, v -> sk.rotate = v);
        floatSlider("Rotation speed", 0f, 5f, 0.1f, () -> sk.rotationSpeed, v -> sk.rotationSpeed = v, "%.1f deg/s");
        toggle("Darken at night", () -> sk.followDayCycle, v -> sk.followDayCycle = v);

        header("Custom skies");
        button("Open custom skies folder", "Open", false, () -> openFolder(client.sky().customDir()));
        note("PNG 3x2 grid of square faces (north, east, south / west, up, down), 384-3072 px wide. See documentation/SKIES.md.");
        if (client.sky().lastError() != null) warn("Last error: " + client.sky().lastError());
    }

    private void buildEffects(final ClientConfig.KillEffects ke) {
        header("Kill effects");
        toggle("Kill effects", () -> ke.enabled, v -> ke.enabled = v);
        List<TileGrid.Item> effectItems = new ArrayList<TileGrid.Item>();
        List<String[]> effectCats = new ArrayList<String[]>();
        for (KillEffectType.Category cat : KillEffectType.Category.values()) {
            effectCats.add(new String[] {cat.name(), cat.label});
        }
        for (KillEffectType t : KillEffectType.values()) {
            effectItems.add(new TileGrid.Item(t.id, t.label, t.category.name(), t.usesColor ? "COLOR" : null));
        }
        grid(new TileGrid(effectItems, effectCats, () -> ke.effect, v -> {
            ke.effect = v;
            changed();
            client.killEffects().preview(KillEffectType.byId(v)); // plays in front of you when in a world
        }, (mc2, it, x, y, w, h) -> drawEffectThumb(KillEffectType.byId(it.id), x, y, w, h), EFFECT_FILTER, 92, 16),
                effectItems, EFFECT_FILTER, "Kill effects");
        note("Click an effect to equip it; in a world it also plays in front of you.");

        header("Effect settings");
        color("Effect colour", () -> ke.color, v -> ke.color = v).describe("Used by effects marked COLOR.");
        button("Preview effect", "Preview", true, () -> client.killEffects().preview());
        floatSlider("Intensity", 0.25f, 2f, 0.05f, () -> ke.intensity, v -> ke.intensity = v, "%.2fx");
        intSlider("Max particles per effect", 0, 200, () -> ke.maxParticles, v -> ke.maxParticles = v);
        toggle("Sounds", () -> ke.sounds, v -> ke.sounds = v);
        floatSlider("Volume", 0f, 1f, 0.05f, () -> ke.volume, v -> ke.volume = v, "%.2f");

        header("Detection");
        toggle("Detect from health (0 HP)", () -> ke.healthDetection, v -> ke.healthDetection = v);
        text("Credits you only when your hit was the last damage and nobody else was attacking.");
        toggle("Detect from chat kill messages", () -> ke.chatDetection, v -> ke.chatDetection = v);
        text("Needs a kill message that makes you the killer.");
        note("Effects play only for players you kill yourself: never for other players' kills, mobs, fall/lava/void "
                + "deaths or your own death. When it is unclear who got the kill, nothing plays. "
                + "Effects are visual only and visible to you only.");
    }

    private List<String>[] styleLists() {
        List<String> ids = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        for (SwingStyle s : SwingStyle.values()) {
            ids.add(s.id);
            names.add(s.label);
        }
        @SuppressWarnings("unchecked")
        List<String>[] r = new List[] {ids, names};
        return r;
    }

    private void buildAnimations(final ClientConfig.Animations an) {
        header("Animations");
        button("Preview swing", "Preview", true, FirstPersonAnimator::previewSwing)
                .describe("Changes only how your sword and tools move in first person. Attacks, their timing, cooldown, reach, "
                        + "hit registration and your rotation are untouched; the animation simply starts when the game's own swing does.");
        List<String>[] styles = styleLists();

        header("Sword");
        toggle("Custom sword animation", () -> an.swordEnabled, v -> an.swordEnabled = v);
        dropdown("Sword style", styles[0], styles[1], () -> an.swordStyle, v -> an.swordStyle = v)
                .describe("Night slash: wind-up, a sweeping arc with a glowing trail, follow-through and a smooth return. "
                        + "Smooth: vanilla's motion with eased curves. Chop: an overhead strike.");
        toggle("Slash trail (Night slash)", () -> an.trail, v -> an.trail = v);
        toggle("Trail in the sword's colour", () -> an.trailItemColor, v -> an.trailItemColor = v);

        header("Weapons and tools");
        toggle("Animate axes, pickaxes, shovels, hoes, rods", () -> an.toolsEnabled, v -> an.toolsEnabled = v);
        text("Bows, food, potions and maps keep Minecraft's own use animations.");
        dropdown("Tool style", styles[0], styles[1], () -> an.toolStyle, v -> an.toolStyle = v);

        header("Feel");
        floatSlider("Speed", 0.5f, 2f, 0.05f, () -> an.speed, v -> an.speed = v, "%.2fx");
        floatSlider("Intensity", 0.5f, 1.5f, 0.05f, () -> an.intensity, v -> an.intensity = v, "%.2fx");

        header("First person");
        toggle("Smooth item switching", () -> an.smoothSwitch, v -> an.smoothSwitch = v);
        text("Eases the hand when you change slots. The new item is selected instantly, as in vanilla.");
        toggle("Smooth blocking", () -> an.smoothBlock, v -> an.smoothBlock = v);
        toggle("Show swing while blocking (visual)", () -> an.swingWhileBlocking, v -> an.swingWhileBlocking = v);
        toggle("Idle sway", () -> an.idleSway, v -> an.idleSway = v);
        button("Restore vanilla animations", "Restore", false, () -> {
            restoreVanillaAnimations(an);
            changed();
            rebuild();
        });
    }

    private static void restoreVanillaAnimations(ClientConfig.Animations an) {
        an.swordEnabled = false;
        an.swordStyle = SwingStyle.VANILLA.id;
        an.toolsEnabled = false;
        an.toolStyle = SwingStyle.VANILLA.id;
        an.trail = false;
        an.smoothSwitch = false;
        an.smoothBlock = false;
        an.swingWhileBlocking = false;
        an.idleSway = false;
        an.speed = 1f;
        an.intensity = 1f;
    }

    private static void restoreVanillaVisuals(ClientConfig.Visuals vs) {
        vs.hitSparks = false;
        vs.arrowTrails = false;
        vs.arrowImpacts = false;
        vs.xpOrbGlow = false;
        vs.refinedWeather = false;
    }

    private void buildPerformance(final ClientConfig c) {
        header("Frame time");
        final FrameStats fs = client.performance().frameStats();
        add(new Preview(30, (mc, cx, cy, bw, bh) -> {
            String s = fs.samples() < 30 ? "Measuring frame times..."
                    : String.format("avg %d FPS   1%% low %d FPS   worst %.1f ms", fs.avgFps(), fs.onePercentLowFps(), fs.maxFrameMs());
            s = Ui.fit(mc.fontRendererObj, s, bw - 8);
            mc.fontRendererObj.drawString(s, cx - mc.fontRendererObj.getStringWidth(s) / 2, cy - 4, Theme.TEXT);
        }));

        header("Presets");
        for (final Preset p : Preset.values()) {
            button(p.label, null, false, () -> {
                client.applyPreset(p);
                rebuild();
            }).selected(p.id.equals(c.activePreset));
        }
        toggle("Also apply video settings", () -> c.performance.applyVanillaSettings, v -> c.performance.applyVanillaSettings = v);
        text("Presets also set render distance, particles, graphics and entity shadows "
                + "(Potato 6/minimal/fast/off, Competitive 8/decreased/fast/off, Visual 12/all/fancy/on).");
        note("Presets change effect, sky and animation settings only. Your HUD layout, crosshair, cape and keybinds are kept.");

        header("Client overhead");
        intSlider("HUD refresh interval (ms)", 16, 500, () -> c.performance.hudRefreshMs, v -> c.performance.hudRefreshMs = v);
        note("The custom sky, capes and kill effects can each be switched off under Cosmetics. "
                + "Render distance, particles and other vanilla options remain in Options > Video Settings.");
    }

    private static String keyLabel(String description) {
        if ("Open Kestrel menu".equals(description)) return "Open Night menu";
        if ("Sumo menu (Sumo practice)".equals(description)) return "Practice menu (Sumo, Open Map)";
        return description;
    }

    private void buildOther(final ClientConfig c) {
        header("Keybinds");
        for (KeyBinding kb : mc.gameSettings.keyBindings) {
            if (!InputManager.CATEGORY.equals(kb.getKeyCategory())) continue;
            add(new KeybindRow(keyLabel(kb.getKeyDescription()), kb));
        }
        note("Click a key, then press the new one. Backspace clears it, Esc cancels. "
                + "Keys are saved with your Minecraft controls (also under Options > Controls).");

        header("Restore");
        button("Restore vanilla visuals", "Restore", false, () -> {
            restoreVanillaVisuals(c.visuals);
            changed();
            rebuild();
        }).describe("Hit sparks, projectile trails, impact bursts, orb glow and refined weather off.");
        button("Restore vanilla look", "Restore", false, () -> {
            restoreVanillaAnimations(c.animations);
            restoreVanillaVisuals(c.visuals);
            c.killEffects.enabled = false;
            changed();
            rebuild();
        }).describe("Animations, visuals and kill effects back to vanilla. Each option can be switched back on individually.");

        header("About");
        add(new NoteRow("Night " + client.version(), Theme.TEXT));
        note("Settings apply immediately and save automatically. Right Shift or Esc closes this menu.");
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private int maxScroll() {
        return Math.max(0, contentHeight - contentH);
    }

    private void layout() {
        int n = content.size();
        if (relY.length < n) relY = new int[n];
        int cards = 0;
        for (Widget w : content) if (w instanceof SectionHeader) cards++;
        cards++; // an untitled card before the first header
        if (cardTop.length < cards) {
            cardTop = new int[cards];
            cardBottom = new int[cards];
            cardFirst = new int[cards];
        }
        cardCount = 0;
        int yy = 0;
        boolean inCard = false;
        for (int i = 0; i < n; i++) {
            Widget w = content.get(i);
            if (w instanceof SectionHeader || !inCard) {
                if (inCard) {
                    cardBottom[cardCount - 1] = yy + 4;
                    yy += 4 + CARD_GAP;
                }
                cardTop[cardCount] = yy;
                cardFirst[cardCount] = i;
                cardCount++;
                inCard = true;
                if (!(w instanceof SectionHeader)) yy += 4;
            }
            int pad = 0;
            if (w instanceof Row) {
                w.x = contentX + 1;
                ((Row) w).layout(mc, innerW - 2);
            } else if (w instanceof NoteRow) {
                w.x = contentX;
                ((NoteRow) w).layout(mc, innerW);
            } else if (w instanceof TileGrid) {
                pad = 4;
                w.x = contentX + 8;
                ((TileGrid) w).relayout(mc, innerW - 16);
            } else if (w instanceof Preview) {
                pad = 4;
                w.x = contentX + 8;
                w.w = innerW - 16;
            } else {
                w.x = contentX;
                w.w = innerW;
            }
            yy += pad;
            relY[i] = yy;
            yy += w.h + pad;
        }
        if (inCard) {
            cardBottom[cardCount - 1] = yy + 4;
            yy += 4;
        }
        contentHeight = yy;
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
        scrollShown = Math.max(0, Math.min(scrollShown, maxScroll()));
        applyScroll();
    }

    /** Move widgets to the shown scroll offset (no re-wrapping). */
    private void applyScroll() {
        int off = contentY - Math.round(scrollShown);
        for (int i = 0; i < content.size(); i++) content.get(i).y = off + relY[i];
    }

    private void drawCapePreview(Minecraft mc, int cx, int cy) {
        ResourceLocation tex = client.capes().currentTexture();
        if (tex == null) {
            String s = cfg().cosmetics.capesEnabled ? "No cape selected" : "Capes hidden";
            String sel = cfg().cosmetics.cape;
            if (CapeManager.NONE.equals(sel) || !cfg().cosmetics.capesEnabled || !client.capes().visible(sel)) {
                // A selection this account can't see shows as no cape, without naming it.
                mc.fontRendererObj.drawString(s, cx - mc.fontRendererObj.getStringWidth(s) / 2, cy - 4, Theme.MUTED);
            } else {
                // A cape that failed to load says why; otherwise it is still loading.
                String why = client.capes().lastError();
                String l = why == null ? "Loading..." : mc.fontRendererObj.trimStringToWidth("Not shown: " + why, contentW - 20);
                mc.fontRendererObj.drawString(l, cx - mc.fontRendererObj.getStringWidth(l) / 2, cy - 4, Theme.MUTED);
            }
            return;
        }
        // Outer face of the cape: u=1..11, v=1..17 in the 64x32 layout (10x16 texels).
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableBlend();
        mc.getTextureManager().bindTexture(tex);
        int w = 30;
        int h = 48;
        Gui.drawScaledCustomSizeModalRect(cx - w / 2, cy - h / 2, 1, 1, 10, 16, w, h, 64, 32);
        GlStateManager.disableBlend();
    }

    /** Texture size in pixels (bound once, then cached). */
    private int[] textureSize(Minecraft mc, ResourceLocation tex) {
        int[] size = THUMB_SIZES.get(tex);
        if (size == null) {
            mc.getTextureManager().bindTexture(tex);
            size = new int[] {
                    Math.max(1, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)),
                    Math.max(1, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT))};
            THUMB_SIZES.put(tex, size);
        }
        return size;
    }

    private static void placeholder(Minecraft mc, String label, int x, int y, int w, int h) {
        RenderUtil.rect(x, y, x + w, y + h, Theme.INSET);
        mc.fontRendererObj.drawString(label, x + (w - mc.fontRendererObj.getStringWidth(label)) / 2, y + (h - 8) / 2, Theme.MUTED, false);
    }

    /**
     * Cape tile: the outer face from the catalog thumbnail (animated capes have
     * their frames side by side and play at the cape's own rate). Custom capes
     * show the live texture when equipped.
     */
    private void drawCapeThumb(Minecraft mc, CapeManager.Option o, int x, int y, int w, int h) {
        int fw = h * 10 / 16; // one 10:16 face, centred
        int fx = x + (w - fw) / 2;
        if (o != null && o.mojang != null) {
            ResourceLocation tex = client.capes().previewTexture(o.id);
            if (tex == null) {
                placeholder(mc, client.capes().previewError(o.id) != null ? "offline" : "...", x, y, w, h);
                return;
            }
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.enableBlend();
            mc.getTextureManager().bindTexture(tex);
            Gui.drawScaledCustomSizeModalRect(fx, y, 1, 1, 10, 16, fw, h, 64, 32);
            GlStateManager.disableBlend();
            return;
        }
        if (o == null || o.entry == null) {
            ResourceLocation live = o != null && o.id.equals(cfg().cosmetics.cape) ? client.capes().currentTexture() : null;
            if (live == null) {
                placeholder(mc, o == null || CapeManager.NONE.equals(o.id) ? "-" : "file", x, y, w, h);
                return;
            }
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.enableBlend();
            mc.getTextureManager().bindTexture(live);
            Gui.drawScaledCustomSizeModalRect(fx, y, 1, 1, 10, 16, fw, h, 64, 32);
            GlStateManager.disableBlend();
            return;
        }
        ResourceLocation tex = new ResourceLocation("kestrel", "textures/" + o.entry.thumbnail());
        int[] size = textureSize(mc, tex);
        int frameW = Math.max(1, size[1] * 10 / 16);
        int frames = Math.max(1, size[0] / frameW);
        int frame = 0;
        if (frames > 1 && cfg().cosmetics.animatedCapes) {
            int fps = o.entry.fps > 0 ? o.entry.fps : Math.max(1, cfg().cosmetics.animationFps);
            frame = (int) ((System.currentTimeMillis() * fps / 1000L) % frames);
        }
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableBlend();
        mc.getTextureManager().bindTexture(tex);
        Gui.drawScaledCustomSizeModalRect(fx, y, frame * frameW, 0, frameW, size[1], fw, h, size[0], size[1]);
        GlStateManager.disableBlend();
    }

    /** Sky tile: the north/east/south strip from the catalog thumbnail. */
    private void drawSkyThumb(Minecraft mc, SkyManager.Option o, TileGrid.Item it, int x, int y, int w, int h) {
        if (VANILLA_SKY.equals(it.id)) {
            RenderUtil.rect(x, y, x + w, y + h * 2 / 3, 0xFF79A6FF);
            RenderUtil.rect(x, y + h * 2 / 3, x + w, y + h, 0xFF5C8A3C);
            return;
        }
        if (o == null || o.thumbnail == null) {
            placeholder(mc, "custom", x, y, w, h);
            return;
        }
        ResourceLocation tex = new ResourceLocation("kestrel", "textures/" + o.thumbnail);
        int[] size = textureSize(mc, tex);
        GlStateManager.color(1, 1, 1, 1);
        mc.getTextureManager().bindTexture(tex);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, size[0], size[1], w, h, size[0], size[1]);
    }

    /** Effect tile: a small animated strip in the effect's colours. */
    private void drawEffectThumb(KillEffectType t, int x, int y, int w, int h) {
        RenderUtil.rect(x, y, x + w, y + h, Theme.INSET);
        int base;
        if (t.usesColor) {
            base = cfg().killEffects.color | 0xFF000000;
        } else if (t.category == KillEffectType.Category.ADVANCED) {
            base = ADVANCED_TINT[(t.ordinal() - KillEffectType.BOLT_STRIKE.ordinal()) % ADVANCED_TINT.length];
        } else {
            base = EFFECT_TINT[t.ordinal() % EFFECT_TINT.length];
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 9; i++) {
            // Deterministic per effect and dot; drifts outward from the centre on a loop.
            int seed = t.ordinal() * 31 + i * 7;
            float phase = ((now / 16 + seed * 37) % 100) / 100f;
            float dir = (i % 2 == 0 ? 1 : -1) * (0.2f + (seed % 5) * 0.2f);
            int dx = x + w / 2 + Math.round(dir * phase * (w / 2 - 3));
            int dy = y + h / 2 + Math.round(((seed % 3) - 1) * phase * (h / 2 - 3));
            int alpha = Math.round((1f - phase) * 255f);
            RenderUtil.rect(dx - 1, dy - 1, dx + 1, dy + 1, (alpha << 24) | (base & 0xFFFFFF));
        }
    }

    /** Preview tints for effects that do not use the colour setting (by ordinal, cycled). */
    private static final int[] EFFECT_TINT = {
            0xFFFFF0C8, 0xFFBE5AFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFC88C, 0xFF9A9A9A, 0xFF55FF55,
            0xFFDCE6FF, 0xFFFF9628, 0xFF8CC8FF, 0xFFB4F0FF, 0xFFFF5A28, 0xFFC8C8C8,
            0xFFC8B4FF, 0xFF7AF0E6, 0xFFB45AFF, 0xFF8C3CFF, 0xFFDC64FF, 0xFFFFFFFF,
            0xFFFF5064, 0xFFFFFFFF, 0xFFFFDC3C, 0xFFFF7F00, 0xFFFFF5B4, 0xFFFF8C28};

    /** Thumbnail tints of the 3D effects, in KillEffectType order from BOLT_STRIKE (colour-setting ones unused). */
    private static final int[] ADVANCED_TINT = {
            0xFF9CC0FF, 0xFFFFFFFF, 0xFFB04CFF, 0xFFFF5CF4, 0xFFC9A6FF, 0xFFFF8A2A, 0xFFCFF4FF,
            0xFFB060FF, 0xFFFF9A30, 0xFFBFEFFF, 0xFF3FA8FF, 0xFFA040FF, 0xFFFFE070, 0xFFFFD54A,
            0xFF9A40FF, 0xFFFFFFFF, 0xFFFF7A1A, 0xFF9FE8FF, 0xFFFFFFFF, 0xFFE0662A};

    private void openFolder(java.io.File dir) {
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            java.awt.Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            com.kestrel.KestrelMod.LOG.warn("[Kestrel] cannot open {}: {}", dir, e.toString());
        }
    }


    // =====================================================================
    // Drawing
    // =====================================================================

    private float openAmount(long now) {
        if (closeStart != 0) return 1f - Anim.easeOut(Anim.progress(closeStart, now, CLOSE_MS));
        return Anim.easeOut(Anim.progress(openStart, now, OPEN_MS));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.currentTimeMillis();
        Ui.beginFrame(now, lastFrame);
        lastFrame = now;
        if (closeStart != 0 && now - closeStart >= CLOSE_MS) {
            finishClose();
            return;
        }
        float open = openAmount(now);
        FontRenderer f = fontRendererObj;

        if (mc.theWorld == null) drawGradientRect(0, 0, width, height, 0xFF140B24, 0xFF07040D);
        RenderUtil.rect(0, 0, width, height, Anim.fade(Theme.BACKDROP, open));

        int lift = Math.round((1f - open) * 8f);
        int my = mouseY - lift;
        boolean interactive = closeStart == 0;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, lift, 0);

        // Panel with a soft nebula glow along the top.
        RenderUtil.rect(px - 1, py - 1, px + pw + 1, py + ph + 1, 0x40A16BEF);
        RenderUtil.softRect(px, py, px + pw, py + ph, Theme.PANEL);
        drawGradientRect(px + sideW, py, px + pw, py + 44, 0x26A16BEF, 0x00A16BEF);
        RenderUtil.softRect(px, py, px + sideW, py + ph, Theme.SIDEBAR);
        drawGradientRect(px, py, px + sideW, py + 60, 0x30C05AE8, 0x00C05AE8);

        drawSidebar(f, mouseX, my, interactive);
        drawTopBar(f, mouseX, my, interactive);
        if (subTabs != null) subTabs.draw(mc, interactive ? mouseX : -1, my);
        drawContent(mouseX, my, now, interactive);

        if (open < 1f) RenderUtil.rect(px, py, px + pw, py + ph, Anim.fade(Theme.PANEL, 1f - open));
        GlStateManager.popMatrix();

        Popup p = Ui.popup();
        if (p != null) p.draw(mc, mouseX, mouseY);
        Ui.drawTooltip(mc, width, height);
    }

    private void drawSidebar(FontRenderer f, int mx, int my, boolean interactive) {
        if (narrow) {
            f.drawString("N", px + (sideW - f.getStringWidth("N")) / 2, py + 12, Theme.ACCENT, false);
        } else {
            f.drawString("§lNIGHT", px + 12, py + 11, Theme.ACCENT, false);
            f.drawString("client", px + 12 + f.getStringWidth("§lNIGHT") + 4, py + 11, Theme.FAINT, false);
            // A few star pixels.
            RenderUtil.rect(px + sideW - 18, py + 9, px + sideW - 17, py + 10, Theme.LAVENDER);
            RenderUtil.rect(px + sideW - 12, py + 15, px + sideW - 11, py + 16, Theme.ACCENT_DIM);
            RenderUtil.rect(px + sideW - 24, py + 18, px + sideW - 23, py + 19, Theme.ACCENT);
        }
        Category[] cats = Category.values();
        int top = py + 34;
        int sel = searching() ? -1 : category.ordinal();
        if (sideSel < 0 && sel >= 0) sideSel = sel;
        if (sel >= 0) sideSel = Anim.approach(sideSel, sel, 22f, Ui.dt);
        if (sel >= 0) {
            int sy = top + Math.round(sideSel * entryStep);
            RenderUtil.softRect(px + 4, sy, px + sideW - 4, sy + entryStep - 2, Theme.SIDEBAR_SELECTED);
            RenderUtil.rect(px + 4, sy + 3, px + 6, sy + entryStep - 5, Theme.ACCENT);
        }
        for (int i = 0; i < cats.length; i++) {
            int ey = top + i * entryStep;
            boolean hov = interactive && Ui.popup() == null && mx >= px + 4 && mx < px + sideW - 4 && my >= ey && my < ey + entryStep - 2;
            sideHover[i] = Anim.approach(sideHover[i], hov ? 1f : 0f, 18f, Ui.dt);
            if (sideHover[i] > 0.01f && i != sel) {
                RenderUtil.softRect(px + 4, ey, px + sideW - 4, ey + entryStep - 2, Anim.fade(Theme.ROW_HOVER, sideHover[i]));
            }
            int col = i == sel ? Theme.LAVENDER : Anim.mix(Theme.MUTED, Theme.TEXT, sideHover[i]);
            int iy = ey + (entryStep - 2 - 9) / 2;
            if (narrow) {
                Icons.draw(cats[i].icon, px + (sideW - 9) / 2, iy, col);
                if (hov) Ui.tooltip(cats[i].label, mx, my);
            } else {
                Icons.draw(cats[i].icon, px + 12, iy, col);
                f.drawString(cats[i].label, px + 27, ey + (entryStep - 2 - 8) / 2 + 1, i == sel ? Theme.TEXT : col, false);
            }
        }
        if (!narrow) {
            f.drawString(Ui.fit(f, presetLabel, sideW - 20), px + 12, py + ph - 24, Theme.FAINT, false);
            f.drawString(Ui.fit(f, "v" + client.version(), sideW - 20), px + 12, py + ph - 13, Theme.FAINT, false);
        }
    }

    private boolean overBack(int mx, int my) {
        return searching() && mx >= contentX - 2 && mx < contentX + 14 && my >= py + 6 && my < py + 22;
    }

    private boolean overSearch(int mx, int my) {
        return mx >= searchX && mx < searchX + searchW && my >= searchY && my < searchY + SEARCH_H;
    }

    private boolean overClose(int mx, int my) {
        return mx >= closeX - 2 && mx < closeX + 14 && my >= closeY - 2 && my < closeY + 12;
    }

    private void drawTopBar(FontRenderer f, int mx, int my, boolean interactive) {
        boolean free = interactive && Ui.popup() == null;
        int tx = contentX;
        if (searching()) {
            backHover = Anim.approach(backHover, free && overBack(mx, my) ? 1f : 0f, 18f, Ui.dt);
            Icons.draw(Icons.BACK, contentX, py + 10, Anim.mix(Theme.MUTED, Theme.LAVENDER, backHover));
            if (free && overBack(mx, my)) Ui.tooltip("Back to " + category.label, mx, my);
            tx += 14;
        }
        int titleW = searchX - tx - 8;
        f.drawString(Ui.fit(f, title, titleW), tx, py + 11, Theme.TEXT, false);

        // Search box.
        searchHover = Anim.approach(searchHover, free && overSearch(mx, my) ? 1f : 0f, 18f, Ui.dt);
        RenderUtil.softRect(searchX, searchY, searchX + searchW, searchY + SEARCH_H, Anim.mix(Theme.PILL, Theme.PILL_HOVER, searchHover));
        RenderUtil.outline(searchX, searchY, searchX + searchW, searchY + SEARCH_H,
                searchFocused ? Theme.ACCENT : Anim.mix(Theme.SECTION_BORDER, Theme.ACCENT_DIM, searchHover));
        Icons.draw(Icons.SEARCH, searchX + 4, searchY + 4, searchFocused ? Theme.LAVENDER : Theme.FAINT);
        int textX = searchX + 17;
        int textW = searchW - 21;
        if (query.isEmpty()) {
            if (!searchFocused) f.drawString(Ui.fit(f, "Search settings", textW), textX, searchY + 4, Theme.FAINT, false);
        } else {
            String shown = f.getStringWidth(query) <= textW ? query : f.trimStringToWidth(query, textW, true);
            f.drawString(shown, textX, searchY + 4, Theme.TEXT, false);
            textX += f.getStringWidth(shown);
        }
        if (searchFocused && (Ui.now / 500) % 2 == 0) RenderUtil.rect(textX + 1, searchY + 3, textX + 2, searchY + 13, Theme.LAVENDER);

        // Close.
        closeHover = Anim.approach(closeHover, free && overClose(mx, my) ? 1f : 0f, 18f, Ui.dt);
        int cc = Anim.mix(Theme.FAINT, Theme.ERR, closeHover);
        for (int i = 0; i < 9; i++) {
            RenderUtil.rect(closeX + 1 + i, closeY + i, closeX + 2 + i, closeY + i + 1, cc);
            RenderUtil.rect(closeX + 9 - i, closeY + i, closeX + 10 - i, closeY + i + 1, cc);
        }
        if (free && overClose(mx, my)) Ui.tooltip("Close (Esc)", mx, my);

        RenderUtil.rect(contentX, py + TOP_H - 4, contentX + contentW, py + TOP_H - 3, Theme.SEPARATOR);
    }

    private boolean insideContent(int mx, int my) {
        return mx >= contentX && mx < contentX + contentW && my >= contentY && my < contentY + contentH;
    }

    private int switchOffset(long now) {
        float t = Anim.easeOut(Anim.progress(switchStart, now, SWITCH_MS));
        return t >= 1f ? 0 : Math.round((1f - t) * 14f * switchDir);
    }

    private void drawContent(int mouseX, int mouseY, long now, boolean interactive) {
        if (!dragScrollbar) {
            float before = scrollShown;
            scrollShown = Anim.approach(scrollShown, scrollTarget, 18f, Ui.dt);
            if (scrollShown != before) applyScroll();
        }
        float sw = Anim.progress(switchStart, now, SWITCH_MS);
        int off = switchOffset(now);
        boolean inside = interactive && insideContent(mouseX, mouseY);
        int mx = inside ? mouseX - off : -1;
        int my = inside ? mouseY : -1;

        int sf = scaleFactor;
        int lift = Math.round((1f - openAmount(now)) * 8f);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(contentX * sf, mc.displayHeight - (contentY + lift + contentH) * sf, contentW * sf, contentH * sf);
        GlStateManager.pushMatrix();
        GlStateManager.translate(off, 0, 0);

        int scroll = Math.round(scrollShown);
        int viewTop = contentY;
        int viewBottom = contentY + contentH;
        for (int k = 0; k < cardCount; k++) {
            int top = contentY + cardTop[k] - scroll;
            int bottom = contentY + cardBottom[k] - scroll;
            if (bottom < viewTop || top > viewBottom) continue;
            RenderUtil.softRect(contentX, top, contentX + innerW, bottom, Theme.SECTION);
            RenderUtil.outline(contentX, top, contentX + innerW, bottom, Theme.SECTION_BORDER);
        }
        Widget prev = null;
        for (int i = 0; i < content.size(); i++) {
            Widget w = content.get(i);
            boolean visible = w.y + w.h >= viewTop && w.y <= viewBottom;
            if (visible && w instanceof Row && prev instanceof Row) {
                RenderUtil.rect(contentX + 8, w.y, contentX + innerW - 8, w.y + 1, Theme.SEPARATOR);
            }
            prev = w;
            if (!visible) continue;
            w.draw(mc, mx, my);
        }
        GlStateManager.popMatrix();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (sw < 1f) {
            RenderUtil.rect(contentX, contentY, contentX + contentW, contentY + contentH, Anim.fade(Theme.PANEL, 1f - Anim.easeOut(sw)));
        }
        if (contentHeight > contentH) {
            scrollbar();
            boolean hov = interactive && Ui.popup() == null && mouseX >= contentX + contentW - 6 && mouseX < contentX + contentW + 2
                    && mouseY >= contentY && mouseY < contentY + contentH;
            int bx = contentX + contentW - 3;
            RenderUtil.rect(bx, contentY, bx + 2, contentY + contentH, Theme.TRACK);
            RenderUtil.rect(bx, barY, bx + 2, barY + barH, dragScrollbar || hov ? Theme.ACCENT : Theme.ACCENT_DIM);
        }
    }

    /** Update {@link #barY} and {@link #barH}, the scrollbar thumb. */
    private void scrollbar() {
        barH = Math.max(16, contentH * contentH / Math.max(1, contentHeight));
        int max = maxScroll();
        barY = contentY + (max == 0 ? 0 : Math.round((contentH - barH) * scrollShown / max));
    }

    // =====================================================================
    // Input
    // =====================================================================

    private KeybindRow listeningRow() {
        for (Widget w : content) {
            if (w instanceof KeybindRow && ((KeybindRow) w).isListening()) return (KeybindRow) w;
        }
        return null;
    }

    private TileGrid focusedGrid() {
        for (Widget w : content) {
            if (w instanceof TileGrid && ((TileGrid) w).isSearchFocused()) return (TileGrid) w;
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (closeStart != 0) return;
        KeybindRow listening = listeningRow();
        if (listening != null) {
            listening.mouseClicked(mouseX, mouseY, button); // binds a mouse button
            return;
        }
        Popup p = Ui.popup();
        if (p != null) {
            if (p.contains(mouseX, mouseY)) {
                p.mouseClicked(mouseX, mouseY, button);
                dragPopup = true;
            } else {
                Ui.closePopup();
            }
            return;
        }
        boolean wasFocused = searchFocused;
        searchFocused = false;
        if (button != 0 && !insideContent(mouseX, mouseY)) return;

        if (overClose(mouseX, mouseY)) {
            Ui.click();
            startClose();
            return;
        }
        if (overBack(mouseX, mouseY)) {
            Ui.click();
            setQuery("");
            return;
        }
        if (overSearch(mouseX, mouseY)) {
            searchFocused = true;
            if (!wasFocused) Ui.click();
            return;
        }
        Category[] cats = Category.values();
        int top = py + 34;
        if (mouseX >= px + 4 && mouseX < px + sideW - 4 && mouseY >= top && mouseY < top + cats.length * entryStep) {
            int i = (mouseY - top) / entryStep;
            if (i >= 0 && i < cats.length) {
                Ui.click();
                select(cats[i], cats[i] == Category.COSMETICS ? lastCosmeticsTab : 0);
            }
            return;
        }
        if (subTabs != null && subTabs.mouseClicked(mouseX, mouseY, button)) return;

        if (contentHeight > contentH && mouseX >= contentX + contentW - 6 && mouseX < contentX + contentW + 2
                && mouseY >= contentY && mouseY < contentY + contentH) {
            scrollbar();
            dragGrab = mouseY >= barY && mouseY < barY + barH ? mouseY - barY : barH / 2;
            dragScrollbar = true;
            dragScrollTo(mouseY);
            return;
        }

        boolean inside = insideContent(mouseX, mouseY);
        int mx = mouseX - switchOffset(System.currentTimeMillis());
        // Clicking anywhere else takes the focus away from a grid's search box.
        for (Widget w : content) {
            if (w instanceof TileGrid && !(inside && w.isHovered(mx, mouseY))) w.mouseClicked(-1, -1, 0);
        }
        if (!inside) return;
        // Copy: a click may rebuild the content list.
        for (Widget w : new ArrayList<Widget>(content)) {
            if (w.y + w.h < contentY || w.y > contentY + contentH) continue;
            if (w.mouseClicked(mx, mouseY, button)) {
                dragging = w;
                if (w instanceof TileGrid) layout(); // its height follows the filter
                return;
            }
        }
    }

    private void dragScrollTo(int mouseY) {
        scrollbar();
        int range = contentH - barH;
        float t = range <= 0 ? 0 : (mouseY - dragGrab - contentY) / (float) range;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        scrollTarget = t * maxScroll();
        scrollShown = scrollTarget;
        applyScroll();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (dragPopup && Ui.popup() != null) {
            Ui.popup().mouseDragged(mouseX, mouseY);
        } else if (dragScrollbar) {
            dragScrollTo(mouseY);
        } else if (dragging != null) {
            dragging.mouseDragged(mouseX - switchOffset(System.currentTimeMillis()), mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (dragPopup) {
            if (Ui.popup() != null) Ui.popup().mouseReleased();
            dragPopup = false;
        }
        dragScrollbar = false;
        if (dragging != null) {
            dragging.mouseReleased(mouseX, mouseY);
            dragging = null;
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || closeStart != 0) return;
        int dir = Integer.signum(wheel);
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        Popup p = Ui.popup();
        if (p != null) {
            if (p.contains(mx, my)) p.scroll(dir);
            return;
        }
        if (dragScrollbar) return;
        scrollTarget = Math.max(0, Math.min(scrollTarget - dir * 36, maxScroll()));
    }

    private boolean isOpenKey(int keyCode) {
        int code = openKey != null ? openKey.getKeyCode() : Keyboard.KEY_RSHIFT;
        return code > 0 && keyCode == code;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (closeStart != 0) return;
        KeybindRow listening = listeningRow();
        if (listening != null) {
            listening.keyTyped(typedChar, keyCode); // Esc cancels, Backspace clears, anything else binds
            return;
        }
        Popup p = Ui.popup();
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (p != null) Ui.closePopup();
            else startClose();
            return;
        }
        if (p != null) {
            if (!p.keyTyped(typedChar, keyCode) && isOpenKey(keyCode)) startClose();
            return;
        }
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        if (ctrl && keyCode == Keyboard.KEY_F) {
            TileGrid g = focusedGrid();
            if (g != null) g.mouseClicked(-1, -1, 0);
            searchFocused = true;
            return;
        }
        if (isOpenKey(keyCode) && !printable(typedChar)) {
            // Right Shift types nothing, so it closes the menu even while a search box is focused.
            startClose();
            return;
        }
        if (searchFocused) {
            searchKey(typedChar, keyCode, ctrl);
            return;
        }
        TileGrid g = focusedGrid();
        if (g != null) {
            g.keyTyped(typedChar, keyCode);
            layout(); // the grid's height changes with the filter
            return;
        }
        if (isOpenKey(keyCode)) {
            startClose();
            return;
        }
        if (printable(typedChar)) {
            // Typing anywhere starts a search.
            searchFocused = true;
            setQuery(query + typedChar);
        }
    }

    private static boolean printable(char c) {
        return c >= ' ' && c != 127 && c != '§';
    }

    private void searchKey(char c, int key, boolean ctrl) {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER || key == Keyboard.KEY_TAB) {
            searchFocused = false;
        } else if (key == Keyboard.KEY_BACK) {
            if (query.isEmpty()) return;
            setQuery(ctrl ? "" : query.substring(0, query.length() - 1));
        } else if (ctrl && key == Keyboard.KEY_A) {
            return; // no selection in this field; Ctrl+Backspace clears it
        } else if (printable(c) && query.length() < MAX_QUERY) {
            setQuery(query + c);
        }
    }

    private void startClose() {
        if (closeStart != 0) return;
        KeybindRow listening = listeningRow();
        if (listening != null) listening.cancel();
        Ui.closePopup();
        searchFocused = false;
        long now = System.currentTimeMillis();
        // Closing while still opening starts from the current state.
        float open = openAmount(now);
        closeStart = now - Math.round((1f - open) * CLOSE_MS);
    }

    private void finishClose() {
        // Keys pressed while the menu was open count as presses for the game's
        // bindings too; drop them so nothing fires (or reopens this menu) afterwards.
        for (KeyBinding kb : mc.gameSettings.keyBindings) {
            while (kb.isPressed()) {
                // drain
            }
        }
        mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        Ui.closePopup();
        client.configManager().markDirty();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false; // lets kill-effect previews and sky rotation animate
    }
}
