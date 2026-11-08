package com.kestrel.sumo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SumoStatsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void recordsResultsStreaksAndDurations() {
        SumoStats s = new SumoStats();
        s.record(SumoStats.Result.WIN, 12_000, 9, 4, 1);
        s.record(SumoStats.Result.WIN, 8_000, 7, 3, 2);
        s.record(SumoStats.Result.WIN, 10_000, 5, 5, 3);
        assertEquals(3, s.currentStreak);
        assertEquals(3, s.bestWinStreak);
        s.record(SumoStats.Result.LOSS, 20_000, 2, 8, 4);
        assertEquals(-1, s.currentStreak);
        assertEquals(3, s.bestWinStreak);
        s.record(SumoStats.Result.DRAW, 180_000, 0, 0, 5);
        assertEquals(5, s.matches);
        assertEquals(3, s.wins);
        assertEquals(1, s.losses);
        assertEquals(1, s.draws);
        assertEquals(8_000, s.fastestWinMs);
        assertEquals(180_000, s.longestMatchMs);
        assertEquals((12 + 8 + 10 + 20 + 180) * 1000 / 5, s.averageMatchMs());
        assertEquals(23, s.hitsLanded);
        assertEquals(20, s.hitsTaken);
        assertEquals(60, s.winRatePercent());
        assertEquals(5, s.recentMatchMs.size());
    }

    @Test
    public void winsRaiseAndLossesLowerTheDifficulty() {
        SumoStats s = new SumoStats();
        double start = s.difficulty;
        s.record(SumoStats.Result.WIN, 1000, 0, 0, 0);
        assertTrue(s.difficulty > start);
        double afterWin = s.difficulty;
        s.record(SumoStats.Result.LOSS, 1000, 0, 0, 0);
        assertTrue(s.difficulty < afterWin);
        double beforeDraw = s.difficulty;
        s.record(SumoStats.Result.DRAW, 1000, 0, 0, 0);
        assertEquals(beforeDraw, s.difficulty, 1e-12);
        for (int i = 0; i < 100; i++) s.record(SumoStats.Result.WIN, 1000, 0, 0, 0);
        assertEquals(SumoDifficulty.MAX, s.difficulty, 1e-12);
        for (int i = 0; i < 100; i++) s.record(SumoStats.Result.LOSS, 1000, 0, 0, 0);
        assertEquals(SumoDifficulty.MIN, s.difficulty, 1e-12);
    }

    @Test
    public void savesAndLoadsEverythingIncludingLearning() throws Exception {
        File f = SumoStats.file(tmp.getRoot());
        SumoStats s = new SumoStats();
        s.record(SumoStats.Result.WIN, 9000, 6, 2, 42);
        s.profile.leftTicks = 300;
        s.profile.rightTicks = 100;
        s.profile.recordStrafeRun(10);
        s.save(f);
        assertFalse(new File(f.getPath() + ".tmp").exists());

        SumoStats back = SumoStats.load(f);
        assertEquals(1, back.matches);
        assertEquals(1, back.wins);
        assertEquals(s.difficulty, back.difficulty, 1e-12);
        assertEquals(300, back.profile.leftTicks);
        assertEquals(10.0, back.profile.avgStrafeRun, 1e-9);
        assertEquals(42, back.lastPlayedEpochMs);
    }

    @Test
    public void missingFileStartsFresh() {
        SumoStats s = SumoStats.load(new File(tmp.getRoot(), "nope.json"));
        assertEquals(0, s.matches);
        assertEquals(SumoDifficulty.START, s.difficulty, 1e-12);
    }

    @Test
    public void unreadableFileIsKeptAsideNotDeleted() throws Exception {
        File f = SumoStats.file(tmp.getRoot());
        Files.write(f.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));
        SumoStats s = SumoStats.load(f);
        assertEquals(0, s.matches);
        File[] aside = tmp.getRoot().listFiles((d, n) -> n.startsWith(SumoStats.FILE_NAME + ".unreadable-"));
        assertEquals(1, aside.length);
        assertEquals("{ not json", new String(Files.readAllBytes(aside[0].toPath()), StandardCharsets.UTF_8));
        s.save(f); // a fresh file can now be written
        assertTrue(f.isFile());
    }

    @Test
    public void damagedValuesAreRepaired() throws Exception {
        File f = SumoStats.file(tmp.getRoot());
        Files.write(f.toPath(), ("{\"matches\":-3,\"difficulty\":7,\"recentMatchMs\":null,"
                + "\"profile\":{\"edgeTicks\":50,\"observedTicks\":10,\"avgHitRange\":99}}").getBytes(StandardCharsets.UTF_8));
        SumoStats s = SumoStats.load(f);
        assertEquals(0, s.matches);
        assertEquals(SumoDifficulty.MAX, s.difficulty, 1e-12);
        assertTrue(s.recentMatchMs.isEmpty());
        assertEquals(10, s.profile.edgeTicks);
        assertEquals(0, s.profile.avgHitRange, 1e-12);
    }

    @Test
    public void resetStartsOverAndKeepsABackup() throws Exception {
        File f = SumoStats.file(tmp.getRoot());
        SumoStats s = new SumoStats();
        s.record(SumoStats.Result.WIN, 9000, 6, 2, 42);
        s.save(f);
        SumoStats fresh = SumoStats.reset(f);
        assertEquals(0, fresh.matches);
        assertEquals(0, SumoStats.load(f).matches);
        File[] backups = tmp.getRoot().listFiles((d, n) -> n.startsWith(SumoStats.FILE_NAME + ".reset-"));
        assertEquals(1, backups.length);
        assertEquals(1, SumoStats.load(backups[0]).matches);
    }
}
