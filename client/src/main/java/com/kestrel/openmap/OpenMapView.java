package com.kestrel.openmap;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An immutable picture of the Open Map session for the client side (menu,
 * lobby banner), published by the integrated server thread. Also carries the
 * one-shot request to open the menu.
 */
public final class OpenMapView {
    private static volatile OpenMapView current;
    private static final AtomicBoolean REQUEST = new AtomicBoolean();

    public final String phase;
    public final boolean editing;
    public final boolean canEdit;
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
    public final int botRodHits;
    public final int botSnowballHits;
    public final int botBlocks;
    public final int learnedFrom;
    public final String modeLabel;
    public final boolean adaptive;
    public final int effectivePercent;
    public final int adaptivePercent;
    public final String styleLabel;
    public final String styleDescription;
    public final String playerSpawn;
    public final String aiSpawn;
    public final String lobbySpawn;
    public final double labelX;
    public final double labelY;
    public final double labelZ;

    OpenMapView(OpenMapSession s) {
        PvpStats st = s.stats();
        OpenMapLayout l = s.layout();
        OpenMapPrefs p = s.prefs();
        OpenMapSession.Phase ph = s.phase();
        phase = ph.name();
        editing = ph == OpenMapSession.Phase.EDIT;
        canEdit = s.canEdit();
        matchRunning = ph == OpenMapSession.Phase.COUNTDOWN || ph == OpenMapSession.Phase.FIGHT;
        finished = ph == OpenMapSession.Phase.FINISHED;
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
        botRodHits = st.botRodHits;
        botSnowballHits = st.botSnowballHits;
        botBlocks = st.botBlocksPlaced;
        learnedFrom = st.profile.samples;
        modeLabel = p.mode().label;
        adaptive = p.mode().adaptive();
        effectivePercent = (int) Math.round(p.mode().skill(st.difficulty) * 100);
        adaptivePercent = (int) Math.round(st.difficulty * 100);
        styleLabel = p.style().label;
        styleDescription = p.style().description;
        playerSpawn = String.valueOf(l.playerSpawn);
        aiSpawn = String.valueOf(l.aiSpawn);
        lobbySpawn = String.valueOf(l.lobbySpawn);
        double yaw = Math.toRadians(l.lobbySpawn.yaw);
        labelX = l.lobbySpawn.x - Math.sin(yaw) * 4.5;
        labelY = l.lobbySpawn.y + 2.4;
        labelZ = l.lobbySpawn.z + Math.cos(yaw) * 4.5;
    }

    public static OpenMapView current() {
        return current;
    }

    static void publish(OpenMapView v) {
        current = v;
    }

    static void clear() {
        current = null;
        REQUEST.set(false);
    }

    static void request() {
        REQUEST.set(true);
    }

    public static boolean takeRequest() {
        return REQUEST.getAndSet(false);
    }
}
