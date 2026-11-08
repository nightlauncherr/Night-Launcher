package com.kestrel.sumo;

import com.kestrel.gui.Theme;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * The Sumo menu: START SUMO / REMATCH, statistics, opponent difficulty and
 * playstyle, and arena settings. Every button runs the matching {@code /sumo}
 * command, so the menu and the commands behave the same. Destructive buttons
 * ask for a second click. The game keeps running while it is open.
 */
final class SumoMenuScreen extends GuiScreen {
    private static final int W = 384;
    private static final int H = 224;
    private static final long CONFIRM_MS = 4000;

    private static final int START = 1;
    private static final int SECOND = 2;
    private static final int DIFFICULTY = 10;
    private static final int STYLE = 11;
    private static final int EDIT = 20;
    private static final int SAVE = 21;
    private static final int RESTORE = 22;
    private static final int DEFAULT = 23;
    private static final int SIZE_DOWN = 24;
    private static final int SIZE_UP = 25;
    private static final int MATERIAL = 26;
    private static final int REBUILD = 27;
    private static final int SPAWN_PLAYER = 30;
    private static final int SPAWN_AI = 31;
    private static final int SPAWN_LOBBY = 32;
    private static final int RESET_STATS = 40;

    private final SumoClient client;
    private final Map<Integer, Long> confirmUntil = new HashMap<Integer, Long>();
    private int px;
    private int py;
    private int pendingSize = -1;
    private String pendingMaterial;
    private SumoButton start;
    private SumoButton second;
    private SumoButton difficulty;
    private SumoButton style;
    private SumoButton edit;
    private SumoButton save;
    private SumoButton restore;
    private SumoButton defaults;
    private SumoButton sizeDown;
    private SumoButton sizeUp;
    private SumoButton material;
    private SumoButton rebuild;
    private SumoButton spawnPlayer;
    private SumoButton spawnAi;
    private SumoButton spawnLobby;
    private SumoButton resetStats;

    SumoMenuScreen(SumoClient client) {
        this.client = client;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        px = Math.max(2, (width - W) / 2);
        py = Math.max(2, (height - H) / 2);
        int lx = px + 8;
        int rx = px + 200;
        int rw = W - 208;
        int half = (rw - 4) / 2;
        int by = py + H - 26;
        start = add(new SumoButton(START, lx, by, 120, 20, "START SUMO", true));
        second = add(new SumoButton(SECOND, lx + 124, by, 64, 20, "LOBBY", false));
        resetStats = add(new SumoButton(RESET_STATS, lx, py + 120, 92, 14, "Reset stats", false));

        difficulty = add(new SumoButton(DIFFICULTY, rx, py + 44, rw, 16, "", false));
        style = add(new SumoButton(STYLE, rx, py + 62, rw, 16, "", false));
        int ay = py + 104;
        edit = add(new SumoButton(EDIT, rx, ay, half, 16, "", false));
        save = add(new SumoButton(SAVE, rx + half + 4, ay, half, 16, "Save arena", false));
        restore = add(new SumoButton(RESTORE, rx, ay + 18, half, 16, "Restore save", false));
        defaults = add(new SumoButton(DEFAULT, rx + half + 4, ay + 18, half, 16, "Default arena", false));
        sizeDown = add(new SumoButton(SIZE_DOWN, rx, ay + 36, 16, 16, "-", false));
        sizeUp = add(new SumoButton(SIZE_UP, rx + 56, ay + 36, 16, 16, "+", false));
        material = add(new SumoButton(MATERIAL, rx + 76, ay + 36, rw - 76, 16, "", false));
        rebuild = add(new SumoButton(REBUILD, rx, ay + 54, rw, 16, "", false));
        int third = (rw - 8) / 3;
        spawnPlayer = add(new SumoButton(SPAWN_PLAYER, rx, ay + 84, third, 16, "Player", false));
        spawnAi = add(new SumoButton(SPAWN_AI, rx + third + 4, ay + 84, third, 16, "AI", false));
        spawnLobby = add(new SumoButton(SPAWN_LOBBY, rx + 2 * (third + 4), ay + 84, third, 16, "Lobby", false));
        refresh();
    }

    private SumoButton add(SumoButton b) {
        buttonList.add(b);
        return b;
    }

    @Override
    public void updateScreen() {
        if (SumoView.current() == null || mc.theWorld == null) {
            mc.displayGuiScreen(null);
            return;
        }
        refresh();
    }

    private boolean confirming(int id) {
        Long until = confirmUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    private void refresh() {
        SumoView v = SumoView.current();
        if (v == null) return;
        if (v.editing) {
            start.displayString = "FINISH EDITING";
            second.displayString = "Save";
        } else if (v.matchRunning) {
            start.displayString = "MATCH RUNNING";
            second.displayString = "STOP";
        } else if (v.finished) {
            start.displayString = "REMATCH";
            second.displayString = "LOBBY";
        } else {
            start.displayString = "START SUMO";
            second.displayString = "Edit";
        }
        start.enabled = !v.matchRunning;
        difficulty.displayString = "Difficulty: " + v.modeLabel + (v.adaptive ? " (" + v.effectivePercent + "%)" : "");
        style.displayString = "Playstyle: " + v.styleLabel;
        edit.displayString = v.editing ? "Edit mode: ON" : "Edit mode: OFF";
        edit.highlight = v.editing;
        boolean idle = !v.matchRunning;
        edit.enabled = idle;
        save.enabled = idle;
        restore.enabled = idle && v.hasSavedArena;
        defaults.enabled = idle;
        rebuild.enabled = idle;
        spawnPlayer.enabled = idle;
        spawnAi.enabled = idle;
        spawnLobby.enabled = idle;
        int size = size(v);
        sizeDown.enabled = idle && size > ArenaLayout.MIN_SIZE;
        sizeUp.enabled = idle && size < ArenaLayout.MAX_SIZE;
        material.displayString = "Block: " + ArenaLayout.materialLabel(material(v));
        material.enabled = idle;
        rebuild.displayString = confirming(REBUILD) ? "Click again: rebuild " + size + "x" + size
                : "Rebuild platform " + size + " x " + size;
        restore.displayString = confirming(RESTORE) ? "Click again" : "Restore save";
        defaults.displayString = confirming(DEFAULT) ? "Click again" : "Default arena";
        resetStats.displayString = confirming(RESET_STATS) ? "Click again" : "Reset stats";
        for (Object o : buttonList) {
            SumoButton b = (SumoButton) o;
            b.warn = confirming(b.id);
        }
    }

    private int size(SumoView v) {
        return pendingSize > 0 ? pendingSize : v.platformSize;
    }

    private String material(SumoView v) {
        if (pendingMaterial != null) return pendingMaterial;
        for (String[] m : ArenaLayout.MATERIALS) if (m[1].equals(v.materialLabel)) return m[0];
        return ArenaLayout.MATERIALS[0][0];
    }

    /** A second click within a few seconds confirms. */
    private boolean confirm(int id) {
        if (confirming(id)) {
            confirmUntil.remove(id);
            return true;
        }
        confirmUntil.put(id, System.currentTimeMillis() + CONFIRM_MS);
        return false;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        SumoView v = SumoView.current();
        if (v == null) return;
        switch (b.id) {
            case START:
                if (v.editing) run("/sumo edit off", false);
                else run("/sumo start", true);
                break;
            case SECOND:
                if (v.editing) run("/sumo save", false);
                else if (v.matchRunning) run("/stopsumo", false);
                else if (v.finished) run("/sumo lobby", false);
                else run("/sumo edit", true);
                break;
            case DIFFICULTY:
                run("/sumo difficulty " + modeOf(v).next().name().toLowerCase(), false);
                break;
            case STYLE:
                run("/sumo style " + styleOf(v).next().name().toLowerCase(), false);
                break;
            case EDIT:
                run(v.editing ? "/sumo edit off" : "/sumo edit", !v.editing);
                break;
            case SAVE:
                run("/sumo save", false);
                break;
            case RESTORE:
                if (confirm(RESTORE)) run("/sumo reset confirm", false);
                break;
            case DEFAULT:
                if (confirm(DEFAULT)) run("/sumo reset default confirm", false);
                break;
            case SIZE_DOWN:
                pendingSize = ArenaLayout.clampSize(size(v) - 2);
                break;
            case SIZE_UP:
                pendingSize = ArenaLayout.clampSize(size(v) + 2);
                break;
            case MATERIAL:
                pendingMaterial = ArenaLayout.nextMaterial(material(v));
                break;
            case REBUILD:
                if (confirm(REBUILD)) {
                    run("/sumo platform " + size(v) + " " + material(v) + " confirm", false);
                    pendingSize = -1;
                    pendingMaterial = null;
                }
                break;
            case SPAWN_PLAYER:
                run("/sumo setspawn player", true);
                break;
            case SPAWN_AI:
                run("/sumo setspawn ai", true);
                break;
            case SPAWN_LOBBY:
                run("/sumo setspawn lobby", true);
                break;
            case RESET_STATS:
                if (confirm(RESET_STATS)) run("/sumo resetstats confirm", false);
                break;
            default:
                break;
        }
        refresh();
    }

    private static DifficultyMode modeOf(SumoView v) {
        for (DifficultyMode m : DifficultyMode.values()) if (m.label.equals(v.modeLabel)) return m;
        return DifficultyMode.ADAPTIVE;
    }

    private static Playstyle styleOf(SumoView v) {
        for (Playstyle p : Playstyle.values()) if (p.label.equals(v.styleLabel)) return p;
        return Playstyle.BALANCED;
    }

    private void run(String command, boolean close) {
        if (close) mc.displayGuiScreen(null);
        if (mc.thePlayer != null) mc.thePlayer.sendChatMessage(command);
    }

    @Override
    protected void keyTyped(char c, int key) throws java.io.IOException {
        if (key == client.menuKey().getKeyCode() && key != Keyboard.KEY_NONE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false; // the session keeps running (countdowns, the opponent) while this is open
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        SumoView v = SumoView.current();
        RenderUtil.rect(0, 0, width, height, Theme.BACKDROP);
        RenderUtil.softRect(px, py, px + W, py + H, Theme.PANEL);
        RenderUtil.outline(px, py, px + W, py + H, Theme.ACCENT_DIM);
        if (v == null) {
            super.drawScreen(mx, my, pt);
            return;
        }
        int lx = px + 8;
        int rx = px + 200;
        text("NIGHT SUMO PRACTICE", lx, py + 7, Theme.ACCENT);
        String state = v.editing ? "EDIT MODE" : v.matchRunning ? ("COUNTDOWN".equals(v.phase) ? "STARTING" : "MATCH RUNNING")
                : v.finished ? "MATCH OVER" : "LOBBY";
        int stateColor = v.editing ? Theme.WARN : v.matchRunning ? Theme.OK : Theme.LAVENDER;
        text(state, px + W - 8 - fontRendererObj.getStringWidth(state), py + 7, stateColor);
        String sub;
        if (v.finished) {
            sub = v.lastResult + "§7 in " + String.format("%.1fs", v.lastDurationMs / 1000.0) + ". Session " + v.scorePlayer + " - " + v.scoreBot
                    + ". REMATCH or back to the LOBBY.";
        } else if (v.editing) {
            sub = "Build freely in creative. Save arena keeps it; Finish editing returns to the lobby.";
        } else if (v.matchRunning) {
            sub = "A match is running. STOP (or /stopsumo) ends it without counting it.";
        } else {
            sub = "Knock " + EntitySumoBot.NAME + " off the platform before it knocks you off.";
        }
        text(trim(sub, W - 16), lx, py + 19, Theme.MUTED);
        RenderUtil.rect(lx, py + 30, px + W - 8, py + 31, Theme.TRACK);

        // Statistics.
        text("Statistics", lx, py + 35, Theme.TEXT);
        int y = py + 47;
        String[] stats = {
                "Matches  " + v.matches + "   (" + v.wins + " W  " + v.losses + " L  " + v.draws + " D)",
                "Win rate  " + v.winRate + "%",
                "Streak  " + v.streak + "    Best  " + v.bestStreak,
                "Average match  " + seconds(v.averageMs),
                "Fastest win  " + (v.fastestWinMs > 0 ? seconds(v.fastestWinMs) : "-"),
                "Opponent  " + v.modeLabel + " " + v.effectivePercent + "%, " + v.styleLabel,
                "Adaptive level  " + v.adaptivePercent + "%" + (v.adaptive ? "" : " (paused)"),
        };
        for (String s : stats) {
            text(s, lx, y, Theme.MUTED);
            y += 10;
        }
        text("Start positions", lx, py + 144, Theme.TEXT);
        text(trim("Player  " + v.playerSpawn, 186), lx, py + 155, Theme.MUTED);
        text(trim("AI  " + v.aiSpawn, 186), lx, py + 165, Theme.MUTED);
        text(trim("Lobby  " + v.lobbySpawn, 186), lx, py + 175, Theme.MUTED);

        // Opponent.
        text("Opponent", rx, py + 35, Theme.TEXT);
        text(trim(styleOf(v).description, W - 208), rx, py + 81, Theme.MUTED);

        // Arena.
        text("Arena", rx, py + 94, Theme.TEXT);
        String saved = v.hasSavedArena ? (v.savedEpochMs > 0 ? "saved " + new SimpleDateFormat("MMM d, HH:mm").format(new Date(v.savedEpochMs))
                : "saved") : "no save yet";
        text(saved, px + W - 8 - fontRendererObj.getStringWidth(saved), py + 94, Theme.MUTED);
        String size = String.valueOf(size(v));
        text(size, rx + 36 - fontRendererObj.getStringWidth(size) / 2, py + 144, Theme.TEXT);
        text("Set start to where you stand:", rx, py + 178, Theme.MUTED);
        String key = Keyboard.getKeyName(client.menuKey().getKeyCode());
        text("Esc or " + key + " closes", rx, py + H - 13, Theme.MUTED);
        super.drawScreen(mx, my, pt);
    }

    private void text(String s, int x, int y, int color) {
        fontRendererObj.drawString(s, x, y, color, false);
    }

    private String trim(String s, int w) {
        return fontRendererObj.getStringWidth(s) <= w ? s : fontRendererObj.trimStringToWidth(s, w - 6) + "...";
    }

    private static String seconds(long ms) {
        return ms > 0 ? String.format("%.1fs", ms / 1000.0) : "-";
    }

    /** Night-style button: accent for the main action, dark otherwise. */
    static final class SumoButton extends GuiButton {
        final boolean primary;
        boolean highlight;
        boolean warn;

        SumoButton(int id, int x, int y, int w, int h, String text, boolean primary) {
            super(id, x, y, w, h, text);
            this.primary = primary;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            hovered = mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height;
            int bg;
            int fg;
            if (!enabled) {
                bg = Theme.TRACK;
                fg = Theme.MUTED;
            } else if (warn) {
                bg = Theme.ERR;
                fg = 0xFF111111;
            } else if (primary || highlight) {
                bg = hovered ? Theme.ACCENT_HOVER : Theme.ACCENT;
                fg = 0xFF111111;
            } else {
                bg = hovered ? Theme.CARD_HOVER : Theme.CARD;
                fg = Theme.TEXT;
            }
            RenderUtil.rect(xPosition, yPosition, xPosition + width, yPosition + height, bg);
            RenderUtil.outline(xPosition, yPosition, xPosition + width, yPosition + height, Theme.ACCENT_DIM);
            String s = displayString;
            if (mc.fontRendererObj.getStringWidth(s) > width - 4) s = mc.fontRendererObj.trimStringToWidth(s, width - 4);
            int tw = mc.fontRendererObj.getStringWidth(s);
            mc.fontRendererObj.drawString(s, xPosition + (width - tw) / 2, yPosition + (height - 8) / 2, fg, false);
        }
    }
}
