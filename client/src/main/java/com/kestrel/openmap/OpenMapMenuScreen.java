package com.kestrel.openmap;

import com.kestrel.gui.Theme;
import com.kestrel.sumo.DifficultyMode;
import com.kestrel.sumo.Playstyle;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Open Map PvP menu: START / NEXT ROUND / STOP, statistics, opponent
 * difficulty and playstyle, and (for the map's owner only) the map controls.
 * Every button runs the matching command, which the server checks again.
 * The game keeps running while it is open.
 */
final class OpenMapMenuScreen extends GuiScreen {
    private static final int W = 384;
    private static final int H = 224;
    private static final long CONFIRM_MS = 6000;

    private static final int START = 1;
    private static final int SECOND = 2;
    private static final int DIFFICULTY = 10;
    private static final int STYLE = 11;
    private static final int EDIT = 20;
    private static final int SAVE = 21;
    private static final int RESET = 22;
    private static final int SPAWN_PLAYER = 30;
    private static final int SPAWN_AI = 31;
    private static final int SPAWN_LOBBY = 32;
    private static final int RESET_STATS = 40;

    private final Map<Integer, Long> confirmUntil = new HashMap<Integer, Long>();
    private int px;
    private int py;
    private Btn start;
    private Btn second;
    private Btn difficulty;
    private Btn style;
    private Btn edit;
    private Btn save;
    private Btn reset;
    private Btn spawnPlayer;
    private Btn spawnAi;
    private Btn spawnLobby;
    private Btn resetStats;

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
        start = add(new Btn(START, lx, by, 120, 20, "START", true));
        second = add(new Btn(SECOND, lx + 124, by, 64, 20, "Close", false));
        resetStats = add(new Btn(RESET_STATS, lx, py + 130, 92, 14, "Reset stats", false));
        difficulty = add(new Btn(DIFFICULTY, rx, py + 44, rw, 16, "", false));
        style = add(new Btn(STYLE, rx, py + 62, rw, 16, "", false));
        int ay = py + 108;
        edit = add(new Btn(EDIT, rx, ay, half, 16, "", false));
        save = add(new Btn(SAVE, rx + half + 4, ay, half, 16, "Save map", false));
        int third = (rw - 8) / 3;
        spawnPlayer = add(new Btn(SPAWN_PLAYER, rx, ay + 34, third, 16, "Player", false));
        spawnAi = add(new Btn(SPAWN_AI, rx + third + 4, ay + 34, third, 16, "AI", false));
        spawnLobby = add(new Btn(SPAWN_LOBBY, rx + 2 * (third + 4), ay + 34, third, 16, "Lobby", false));
        reset = add(new Btn(RESET, rx, ay + 56, rw, 16, "", false));
        refresh();
    }

    private Btn add(Btn b) {
        buttonList.add(b);
        return b;
    }

    @Override
    public void updateScreen() {
        if (OpenMapView.current() == null || mc.theWorld == null) {
            mc.displayGuiScreen(null);
            return;
        }
        OpenMapView.takeRequest(); // already open: a request made meanwhile must not reopen it
        refresh();
    }

    private boolean confirming(int id) {
        Long until = confirmUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    private boolean confirm(int id) {
        if (confirming(id)) {
            confirmUntil.remove(id);
            return true;
        }
        confirmUntil.put(id, System.currentTimeMillis() + CONFIRM_MS);
        return false;
    }

    private void refresh() {
        OpenMapView v = OpenMapView.current();
        if (v == null) return;
        if (v.editing) {
            start.displayString = "FINISH EDITING";
            second.displayString = "Save";
        } else if (v.matchRunning || v.finished) {
            start.displayString = v.finished ? "NEXT ROUND NOW" : "MATCH RUNNING";
            second.displayString = "STOP";
        } else {
            start.displayString = "START";
            second.displayString = "Close";
        }
        start.enabled = !v.matchRunning;
        difficulty.displayString = "Difficulty: " + v.modeLabel + (v.adaptive ? " (" + v.effectivePercent + "%)" : "");
        style.displayString = "Playstyle: " + v.styleLabel;
        edit.displayString = v.editing ? "Edit mode: ON" : "Edit mode: OFF";
        edit.highlight = v.editing;
        boolean idle = !v.matchRunning && !v.finished;
        for (Btn b : new Btn[]{edit, save, reset, spawnPlayer, spawnAi, spawnLobby}) {
            b.visible = v.canEdit; // owner only (the server refuses anyone else as well)
            b.enabled = idle;
        }
        reset.displayString = confirming(RESET) ? "Click again: fresh map next time" : "Reset map (fresh terrain)";
        resetStats.displayString = confirming(RESET_STATS) ? "Click again" : "Reset stats";
        for (Object o : buttonList) {
            Btn b = (Btn) o;
            b.warn = confirming(b.id);
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        OpenMapView v = OpenMapView.current();
        if (v == null) return;
        switch (b.id) {
            case START:
                if (v.editing) run("/openmap edit off", false);
                else run("/openmap start", true);
                break;
            case SECOND:
                if (v.editing) run("/openmap save", false);
                else if (v.matchRunning || v.finished) run("/stopopenmap", false);
                else mc.displayGuiScreen(null);
                break;
            case DIFFICULTY:
                run("/openmap difficulty " + modeOf(v).next().name().toLowerCase(Locale.ROOT), false);
                break;
            case STYLE:
                run("/openmap style " + styleOf(v).next().name().toLowerCase(Locale.ROOT), false);
                break;
            case EDIT:
                run(v.editing ? "/openmap edit off" : "/openmap edit", !v.editing);
                break;
            case SAVE:
                run("/openmap save", false);
                break;
            case RESET:
                if (confirm(RESET)) run("/openmap reset confirm", false);
                break;
            case SPAWN_PLAYER:
                run("/openmap setspawn player", true);
                break;
            case SPAWN_AI:
                run("/openmap setspawn ai", true);
                break;
            case SPAWN_LOBBY:
                run("/openmap setspawn lobby", true);
                break;
            case RESET_STATS:
                if (confirm(RESET_STATS)) run("/openmap resetstats confirm", false);
                break;
            default:
                break;
        }
        refresh();
    }

    private static DifficultyMode modeOf(OpenMapView v) {
        for (DifficultyMode m : DifficultyMode.values()) if (m.label.equals(v.modeLabel)) return m;
        return DifficultyMode.ADAPTIVE;
    }

    private static Playstyle styleOf(OpenMapView v) {
        for (Playstyle p : Playstyle.values()) if (p.label.equals(v.styleLabel)) return p;
        return Playstyle.BALANCED;
    }

    private void run(String command, boolean close) {
        if (close) mc.displayGuiScreen(null);
        if (mc.thePlayer != null) mc.thePlayer.sendChatMessage(command);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        int menuKey = OpenMapClient.key().getKeyCode();
        if (key == menuKey && key != Keyboard.KEY_NONE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        OpenMapView v = OpenMapView.current();
        RenderUtil.rect(0, 0, width, height, Theme.BACKDROP);
        RenderUtil.softRect(px, py, px + W, py + H, Theme.PANEL);
        RenderUtil.outline(px, py, px + W, py + H, Theme.ACCENT_DIM);
        if (v == null) {
            super.drawScreen(mx, my, pt);
            return;
        }
        int lx = px + 8;
        int rx = px + 200;
        text("NIGHT OPEN MAP PVP", lx, py + 7, Theme.OK);
        String state = v.editing ? "EDIT MODE" : v.matchRunning ? ("COUNTDOWN".equals(v.phase) ? "STARTING" : "MATCH RUNNING")
                : v.finished ? "ROUND OVER" : "LOBBY";
        int stateColor = v.editing ? Theme.WARN : v.matchRunning ? Theme.OK : Theme.LAVENDER;
        text(state, px + W - 8 - fontRendererObj.getStringWidth(state), py + 7, stateColor);
        String sub;
        if (v.finished) {
            sub = v.lastResult + "§7 in " + String.format("%.1fs", v.lastDurationMs / 1000.0) + ". Session " + v.scorePlayer
                    + " - " + v.scoreBot + ". The next round starts by itself.";
        } else if (v.editing) {
            sub = "Creative: your changes to the map are permanent. Save map writes them now.";
        } else if (v.matchRunning) {
            sub = "A match is running. STOP (or /stopopenmap) ends it without counting it.";
        } else {
            sub = "Diamond kit, open terrain. First death loses the round.";
        }
        text(trim(sub, W - 16), lx, py + 19, Theme.MUTED);
        RenderUtil.rect(lx, py + 30, px + W - 8, py + 31, Theme.TRACK);

        text("Statistics", lx, py + 35, Theme.TEXT);
        int y = py + 47;
        String[] stats = {
                "Matches  " + v.matches + "   (" + v.wins + " W  " + v.losses + " L  " + v.draws + " D)",
                "Win rate  " + v.winRate + "%   Streak " + v.streak + "  Best " + v.bestStreak,
                "Average match  " + seconds(v.averageMs) + "   Fastest win " + (v.fastestWinMs > 0 ? seconds(v.fastestWinMs) : "-"),
                "Its rod hits " + v.botRodHits + ", snowball hits " + v.botSnowballHits,
                "Its blocks placed " + v.botBlocks,
                "Learned from " + v.learnedFrom + " matches",
                "Adaptive level  " + v.adaptivePercent + "%" + (v.adaptive ? "" : " (paused)"),
        };
        for (String s : stats) {
            text(trim(s, 186), lx, y, Theme.MUTED);
            y += 10;
        }
        text("Start positions", lx, py + 152, Theme.TEXT);
        text(trim("Player  " + v.playerSpawn, 186), lx, py + 163, Theme.MUTED);
        text(trim("AI  " + v.aiSpawn, 186), lx, py + 173, Theme.MUTED);
        text(trim("Lobby  " + v.lobbySpawn, 186), lx, py + 183, Theme.MUTED);

        text("Opponent", rx, py + 35, Theme.TEXT);
        text(trim(styleOf(v).description, W - 208), rx, py + 81, Theme.MUTED);
        text("Map", rx, py + 96, Theme.TEXT);
        if (v.canEdit) {
            text("Set start to where you stand:", rx, py + 130, Theme.MUTED);
        } else {
            text("Night Open Map", rx, py + 108, Theme.MUTED);
            text("Editing is limited to the map's owner.", rx, py + 120, Theme.MUTED);
        }
        String key = Keyboard.getKeyName(OpenMapClient.key().getKeyCode());
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

    /** Night-style button. */
    static final class Btn extends GuiButton {
        final boolean primary;
        boolean highlight;
        boolean warn;

        Btn(int id, int x, int y, int w, int h, String text, boolean primary) {
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
