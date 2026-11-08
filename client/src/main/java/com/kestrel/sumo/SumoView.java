package com.kestrel.sumo;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An immutable picture of the running Sumo session for the client side (menu,
 * lobby text). Published by the integrated server thread, read by the client
 * thread; both live in the same singleplayer game. Also carries the server's
 * requests to open the menu.
 */
public final class SumoView {
    public enum Screen { LOBBY, RESULT }

    private static volatile SumoView current;
    private static final AtomicReference<Screen> REQUEST = new AtomicReference<Screen>();

    public final String phase;
    public final boolean editing;
    public final boolean matchRunning;
    public final boolean finished;
    public final String lastResult;
    public final long lastDurationMs;
    public final int scorePlayer;
    public final int scoreBot;
    public final int matches;
    public final int wins;
    public final int losses;
    public final int draws;
    public final int streak;
    public final int bestStreak;
    public final int winRate;
    public final long averageMs;
    public final long fastestWinMs;
    public final String modeLabel;
    public final boolean adaptive;
    public final int effectivePercent;
    public final int adaptivePercent;
    public final String styleLabel;
    public final String styleDescription;
    public final int platformSize;
    public final String materialLabel;
    public final String playerSpawn;
    public final String aiSpawn;
    public final String lobbySpawn;
    public final boolean hasSavedArena;
    public final long savedEpochMs;
    /** Where the lobby text floats. */
    public final double labelX;
    public final double labelY;
    public final double labelZ;

    SumoView(SumoSession s) {
        SumoStats st = s.stats();
        ArenaLayout l = s.layout();
        SumoPrefs p = s.prefs();
        SumoSession.Phase ph = s.phase();
        phase = ph.name();
        editing = ph == SumoSession.Phase.EDIT;
        matchRunning = ph == SumoSession.Phase.COUNTDOWN || ph == SumoSession.Phase.FIGHT;
        finished = ph == SumoSession.Phase.FINISHED;
        lastResult = s.lastResult();
        lastDurationMs = s.lastDurationMs();
        scorePlayer = s.scorePlayer();
        scoreBot = s.scoreBot();
        matches = st.matches;
        wins = st.wins;
        losses = st.losses;
        draws = st.draws;
        streak = st.winStreak();
        bestStreak = st.bestWinStreak;
        winRate = st.winRatePercent();
        averageMs = st.averageMatchMs();
        fastestWinMs = st.fastestWinMs;
        modeLabel = p.mode().label;
        adaptive = p.mode().adaptive();
        effectivePercent = (int) Math.round(p.mode().skill(st.difficulty) * 100);
        adaptivePercent = (int) Math.round(st.difficulty * 100);
        styleLabel = p.style().label;
        styleDescription = p.style().description;
        platformSize = l.platformSize;
        materialLabel = ArenaLayout.materialLabel(l.platformMaterial);
        playerSpawn = l.playerSpawn.toString();
        aiSpawn = l.aiSpawn.toString();
        lobbySpawn = l.lobbySpawn.toString();
        hasSavedArena = s.hasSavedArena();
        savedEpochMs = l.savedEpochMs;
        double yaw = Math.toRadians(l.lobbySpawn.yaw);
        labelX = l.lobbySpawn.x - Math.sin(yaw) * 4.5;
        labelY = l.lobbySpawn.y + 2.4;
        labelZ = l.lobbySpawn.z + Math.cos(yaw) * 4.5;
    }

    /** The current session, or null outside the Sumo world. */
    public static SumoView current() {
        return current;
    }

    static void publish(SumoView v) {
        current = v;
    }

    static void clear() {
        current = null;
        REQUEST.set(null);
    }

    static void request(Screen s) {
        REQUEST.set(s);
    }

    /** Take the pending request to open the menu, if any. */
    public static Screen takeRequest() {
        return REQUEST.getAndSet(null);
    }
}
