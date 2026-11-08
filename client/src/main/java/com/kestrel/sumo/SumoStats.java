package com.kestrel.sumo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Overall Sumo practice statistics, adaptive difficulty and learned behaviour,
 * stored in {@code <instance>/kestrel/sumo-stats.json}, outside any world, so
 * new or regenerated Sumo worlds keep them. Writes are atomic (temp file then
 * move); an unreadable file is kept aside, never deleted.
 */
public final class SumoStats {
    public static final String FILE_NAME = "sumo-stats.json";
    private static final int RECENT = 20;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Result { WIN, LOSS, DRAW }

    public int version = 1;
    public int matches;
    public int wins;
    public int losses;
    public int draws;
    /** Player's current streak: positive = wins in a row, negative = losses in a row. */
    public int currentStreak;
    public int bestWinStreak;
    public long totalMatchMs;
    public long fastestWinMs;
    public long longestMatchMs;
    public List<Long> recentMatchMs = new ArrayList<Long>();
    public int hitsLanded;
    public int hitsTaken;
    public double difficulty = SumoDifficulty.START;
    public long lastPlayedEpochMs;
    public PlayerProfile profile = new PlayerProfile();
    /** Set when an unreadable file could not be moved aside: never overwrite it then. */
    private transient boolean keepExistingFile;

    public static File file(File dataDir) {
        return new File(dataDir, FILE_NAME);
    }

    /** Player's current win streak (0 when the last match was not a win). */
    public int winStreak() {
        return Math.max(currentStreak, 0);
    }

    /**
     * Record a finished match and adjust the difficulty.
     *
     * @param hitsByPlayer hits the player landed on the opponent this match
     * @param hitsByBot    hits the opponent landed on the player this match
     */
    public void record(Result result, long durationMs, int hitsByPlayer, int hitsByBot, long nowEpochMs) {
        durationMs = Math.max(0, durationMs);
        matches++;
        totalMatchMs += durationMs;
        longestMatchMs = Math.max(longestMatchMs, durationMs);
        recentMatchMs.add(durationMs);
        while (recentMatchMs.size() > RECENT) recentMatchMs.remove(0);
        hitsLanded += Math.max(0, hitsByPlayer);
        hitsTaken += Math.max(0, hitsByBot);
        switch (result) {
            case WIN:
                wins++;
                currentStreak = currentStreak > 0 ? currentStreak + 1 : 1;
                bestWinStreak = Math.max(bestWinStreak, currentStreak);
                if (fastestWinMs == 0 || durationMs < fastestWinMs) fastestWinMs = durationMs;
                break;
            case LOSS:
                losses++;
                currentStreak = currentStreak < 0 ? currentStreak - 1 : -1;
                break;
            default:
                draws++;
                break;
        }
        difficulty = SumoDifficulty.afterMatch(difficulty, result, winStreak());
        lastPlayedEpochMs = nowEpochMs;
    }

    public long averageMatchMs() {
        return matches == 0 ? 0 : totalMatchMs / matches;
    }

    /** Win rate in percent (draws count as played). */
    public int winRatePercent() {
        return matches == 0 ? 0 : (int) Math.round(wins * 100.0 / matches);
    }

    /** Load, or start fresh when missing. An unreadable file is moved aside first. */
    public static SumoStats load(File file) {
        if (!file.isFile()) return new SumoStats();
        try (Reader r = new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
            SumoStats s = GSON.fromJson(r, SumoStats.class);
            if (s == null) throw new JsonParseException("empty");
            s.normalize();
            return s;
        } catch (IOException | RuntimeException e) {
            File aside = new File(file.getParentFile(), file.getName() + ".unreadable-" + System.currentTimeMillis());
            SumoStats fresh = new SumoStats();
            // Keep the original either way; if it can't be moved aside, this session doesn't save.
            fresh.keepExistingFile = !file.renameTo(aside);
            return fresh;
        }
    }

    /** Atomically write to {@code file}. */
    public void save(File file) throws IOException {
        if (keepExistingFile) throw new IOException(file + " is unreadable and could not be moved aside; not overwriting it");
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reset everything (statistics, difficulty and learning). The previous file
     * is kept as {@code sumo-stats.json.reset-<time>} so a reset can be undone.
     */
    public static SumoStats reset(File file) throws IOException {
        if (file.isFile()) {
            File backup = new File(file.getParentFile(), file.getName() + ".reset-" + System.currentTimeMillis());
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        SumoStats fresh = new SumoStats();
        fresh.save(file);
        return fresh;
    }

    void normalize() {
        matches = Math.max(0, matches);
        wins = Math.max(0, wins);
        losses = Math.max(0, losses);
        draws = Math.max(0, draws);
        bestWinStreak = Math.max(0, bestWinStreak);
        totalMatchMs = Math.max(0, totalMatchMs);
        fastestWinMs = Math.max(0, fastestWinMs);
        longestMatchMs = Math.max(0, longestMatchMs);
        hitsLanded = Math.max(0, hitsLanded);
        hitsTaken = Math.max(0, hitsTaken);
        if (recentMatchMs == null) recentMatchMs = new ArrayList<Long>();
        recentMatchMs.removeIf(v -> v == null || v < 0);
        while (recentMatchMs.size() > RECENT) recentMatchMs.remove(0);
        difficulty = SumoDifficulty.clamp(difficulty);
        if (profile == null) profile = new PlayerProfile();
        profile.normalize();
    }
}
