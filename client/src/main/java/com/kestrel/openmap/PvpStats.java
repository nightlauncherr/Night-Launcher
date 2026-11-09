package com.kestrel.openmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.kestrel.sumo.SumoDifficulty;
import com.kestrel.sumo.SumoStats;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Open Map PvP statistics and what the opponent learned, in
 * {@code kestrel/openmap/stats.json} (separate from Sumo). Saved atomically;
 * the previous file is kept as {@code .prev}. A damaged file is set aside,
 * never silently replaced.
 */
public final class PvpStats {
    public int matches;
    public int wins;
    public int losses;
    public int draws;
    public int currentStreak;
    public int bestWinStreak;
    public long totalMatchMs;
    public long fastestWinMs;
    public long longestMatchMs;
    public double damageDealt;
    public double damageTaken;
    public int hitsLanded;
    public int hitsTaken;
    public int botRodHits;
    public int botSnowballHits;
    public int botBlocksPlaced;
    public int playerBlocksPlaced;
    public long lastPlayedEpochMs;
    /** Adaptive skill 0..1. */
    public double difficulty = SumoDifficulty.START;
    public PvpProfile profile = new PvpProfile();

    public int winRatePercent() {
        int decided = wins + losses;
        return decided == 0 ? 0 : (int) Math.round(wins * 100.0 / decided);
    }

    public int winStreak() {
        return Math.max(0, currentStreak);
    }

    public long averageMatchMs() {
        return matches == 0 ? 0 : totalMatchMs / matches;
    }

    /** Record a finished match. {@code adapt}: move the adaptive difficulty (only in Adaptive mode). */
    public void record(SumoStats.Result result, long ms, boolean adapt) {
        matches++;
        totalMatchMs += Math.max(0, ms);
        longestMatchMs = Math.max(longestMatchMs, ms);
        lastPlayedEpochMs = System.currentTimeMillis();
        switch (result) {
            case WIN:
                wins++;
                currentStreak = Math.max(0, currentStreak) + 1;
                bestWinStreak = Math.max(bestWinStreak, currentStreak);
                if (fastestWinMs == 0 || ms < fastestWinMs) fastestWinMs = ms;
                break;
            case LOSS:
                losses++;
                currentStreak = Math.min(0, currentStreak) - 1;
                break;
            default:
                draws++;
                break;
        }
        if (adapt) difficulty = SumoDifficulty.afterMatch(difficulty, result, winStreak());
    }

    void normalize() {
        if (profile == null) profile = new PvpProfile();
        profile.normalize();
        if (Double.isNaN(difficulty)) difficulty = SumoDifficulty.START;
        difficulty = SumoDifficulty.clamp(difficulty);
        if (matches < 0) matches = 0;
    }

    public static File file(File dataDir) {
        return new File(new File(dataDir, "openmap"), "stats.json");
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    }

    public static PvpStats load(File f) {
        if (!f.isFile()) return new PvpStats();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            PvpStats s = gson().fromJson(r, PvpStats.class);
            if (s == null) throw new JsonParseException("empty");
            s.normalize();
            return s;
        } catch (IOException | RuntimeException e) {
            File aside = new File(f.getParentFile(), f.getName() + ".damaged-" + System.currentTimeMillis());
            f.renameTo(aside);
            return new PvpStats();
        }
    }

    public void save(File f) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (f.isFile()) Files.copy(f.toPath(), new File(dir, f.getName() + ".prev").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson().toJson(this, w);
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
