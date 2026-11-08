package com.kestrel.sumo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ArenaCoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static GridArena squareGrid(int min, int max) {
        int pad = 3;
        int n = max - min + 1 + 2 * pad;
        boolean[][] w = new boolean[n][n];
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int bx = min - pad + x;
                int bz = min - pad + z;
                w[x][z] = bx >= min && bx <= max && bz >= min && bz <= max;
            }
        }
        return new GridArena(min - pad, min - pad, w);
    }

    @Test
    public void gridOfTheDefaultPlatformMatchesTheSquare() {
        GridArena g = squareGrid(SumoArena.MIN, SumoArena.MAX);
        assertEquals(225, g.walkableCount());
        Random r = new Random(1);
        for (int i = 0; i < 500; i++) {
            double x = SumoArena.minEdge() + r.nextDouble() * 15;
            double z = SumoArena.minEdge() + r.nextDouble() * 15;
            assertEquals("at " + x + "," + z, SumoArena.edgeDistance(x, z), g.edgeDistance(x, z), 0.05);
        }
        assertEquals(SumoArena.CENTER_X, g.safeX(), 0.6);
        assertEquals(SumoArena.CENTER_Z, g.safeZ(), 0.6);
        double[] n = g.nearestEdgeNormal(7.6, 0.5);
        assertTrue("points east: " + n[0], n[0] > 0.9);
        assertTrue("off the grid is outside", g.edgeDistance(40, 40) < 0);
    }

    @Test
    public void gridFollowsACustomShape() {
        // An L-shaped platform: the corner of the L is safer than its thin arm.
        boolean[][] w = new boolean[20][20];
        for (int x = 2; x < 18; x++) for (int z = 2; z < 9; z++) w[x][z] = true;   // wide bar
        for (int x = 2; x < 5; x++) for (int z = 9; z < 18; z++) w[x][z] = true;   // thin arm
        GridArena g = new GridArena(0, 0, w);
        assertTrue(g.edgeDistance(3.5, 15.5) < 2.0);
        assertTrue(g.edgeDistance(9.5, 5.5) > g.edgeDistance(3.5, 15.5));
        assertTrue("safe point on the wide bar: " + g.safeZ(), g.safeZ() < 9);
        assertTrue(g.edgeDistance(10.5, 15.5) < 0); // outside the L
    }

    @Test
    public void brainGuardsTheEdgeOfACustomArena() {
        boolean[][] w = new boolean[40][40];
        for (int x = 10; x < 30; x++) for (int z = 18; z < 22; z++) w[x][z] = true; // 20 x 4 bridge
        GridArena g = new GridArena(0, 0, w);
        SumoBrain b = new SumoBrain(new SumoDifficulty(1.0), new PlayerProfile(), new Random(3));
        b.setArena(g);
        // Standing at the north side of the bridge (z = 18.3), the player to its east.
        SumoBrain.Body self = new SumoBrain.Body().set(15.5, 100, 18.3, -90f, 0f, true, 0);
        SumoBrain.Body target = new SumoBrain.Body().set(19.5, 100, 20.0, 90f, 0f, true, 0);
        for (int i = 0; i < 6; i++) {
            SumoBrain.Controls c = b.tick(self, target);
            double r = Math.toRadians(c.yaw);
            double mz = Math.cos(r) * c.forward + Math.sin(r) * c.strafe;
            assertTrue("moves away from the north edge (+z), tick " + i + ": " + mz, mz > 0);
            self.yaw = c.yaw;
        }
    }

    @Test
    public void playstylesChangeBehaviourWithinTheSameLimits() {
        int aggressiveForward = 0;
        int defensiveForward = 0;
        for (long seed = 0; seed < 10; seed++) {
            aggressiveForward += forwardTicks(Playstyle.AGGRESSIVE, seed);
            defensiveForward += forwardTicks(Playstyle.DEFENSIVE, seed);
        }
        assertTrue(aggressiveForward + " vs " + defensiveForward, aggressiveForward > defensiveForward);
        assertEquals(Playstyle.STRAFER, Playstyle.parse("strafer"));
        assertEquals(Playstyle.BALANCED, Playstyle.STRAFER.next());
        assertNull(Playstyle.parse("nope"));
    }

    private static int forwardTicks(Playstyle style, long seed) {
        SumoBrain b = new SumoBrain(new SumoDifficulty(0.5), new PlayerProfile(), new Random(seed));
        b.setPlaystyle(style);
        SumoBrain.Body self = new SumoBrain.Body().set(3.1, 100, 0.5, 90f, 0f, true, 0);
        SumoBrain.Body target = new SumoBrain.Body().set(0.5, 100, 0.5, -90f, 0f, true, 0); // 2.6 away
        int n = 0;
        for (int i = 0; i < 40; i++) {
            SumoBrain.Controls c = b.tick(self, target);
            if (c.forward > 0) n++;
            self.yaw = c.yaw;
        }
        return n;
    }

    @Test
    public void difficultyModes() {
        assertEquals(0.4, DifficultyMode.ADAPTIVE.skill(0.4), 1e-12);
        assertEquals(0.95, DifficultyMode.EXPERT.skill(0.1), 1e-12);
        assertEquals(DifficultyMode.HARD, DifficultyMode.parse("hard"));
        SumoStats s = new SumoStats();
        double before = s.difficulty;
        s.record(SumoStats.Result.WIN, 1000, 1, 0, 0, false);
        assertEquals("a fixed level keeps the adaptive progress", before, s.difficulty, 1e-12);
        assertEquals(1, s.wins);
    }

    @Test
    public void layoutDefaultsSaveLoadAndBackup() throws Exception {
        ArenaLayout l = ArenaLayout.defaults();
        assertEquals(-3.0, l.playerSpawn.x, 1e-9);
        assertEquals(4.0, l.aiSpawn.x, 1e-9);
        assertEquals(SumoArena.LOSE_Y, l.loseY(), 1e-9);
        assertTrue("lobby clear of the biggest arena", l.lobbySpawn.z + ArenaLayout.LOBBY_HALF < -(ArenaLayout.MAX_SIZE / 2.0));
        assertTrue(l.regionContains(l.lobbySpawn.x, l.lobbySpawn.y, l.lobbySpawn.z));

        File f = ArenaLayout.file(tmp.getRoot());
        l.playerSpawn = new ArenaLayout.Spot(1, 90, 2, 45f);
        l.save(f);
        ArenaLayout back = ArenaLayout.load(f);
        assertNotNull(back);
        assertEquals(90, back.playerSpawn.y, 1e-9);
        assertEquals(87, back.loseY(), 1e-9);
        back.aiSpawn.x = 9;
        back.save(f);
        assertTrue("previous kept", new File(f.getPath() + ".prev").isFile());
        assertEquals(4.0, ArenaLayout.load(new File(f.getPath() + ".prev")).aiSpawn.x, 1e-9);
    }

    @Test
    public void damagedLayoutIsRepaired() throws Exception {
        File f = ArenaLayout.file(tmp.getRoot());
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), "{\"platformSize\":2,\"platformMaterial\":\"lava\",\"playerSpawn\":{\"x\":1,\"y\":-5,\"z\":0},\"regionMinY\":-10}"
                .getBytes(StandardCharsets.UTF_8));
        ArenaLayout l = ArenaLayout.load(f);
        assertEquals(ArenaLayout.MIN_SIZE, l.platformSize);
        assertEquals("light_gray_clay", l.platformMaterial);
        assertEquals(SumoArena.SURFACE_Y, l.playerSpawn.y, 1e-9);
        assertEquals(64, l.regionMinY);
        assertNull(ArenaLayout.load(new File(tmp.getRoot(), "missing.json")));
        Files.write(f.toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        assertNull(ArenaLayout.load(f));
        assertTrue("an unreadable file is left alone", f.isFile());
    }

    @Test
    public void sizesAndMaterials() {
        assertEquals(15, ArenaLayout.clampSize(14 + 1));
        assertEquals(17, ArenaLayout.clampSize(16));
        assertEquals(ArenaLayout.MAX_SIZE, ArenaLayout.clampSize(500));
        assertEquals(3.5, ArenaLayout.spawnOffset(15), 1e-9);
        assertEquals("stone", ArenaLayout.nextMaterial("light_gray_clay"));
        assertEquals("light_gray_clay", ArenaLayout.nextMaterial("black_clay"));
    }

    @Test
    public void prefsRoundTrip() throws Exception {
        File f = SumoPrefs.file(tmp.getRoot());
        SumoPrefs p = new SumoPrefs();
        assertEquals(DifficultyMode.ADAPTIVE, p.mode());
        p.difficulty = "HARD";
        p.playstyle = "STRAFER";
        p.save(f);
        SumoPrefs back = SumoPrefs.load(f);
        assertEquals(DifficultyMode.HARD, back.mode());
        assertEquals(Playstyle.STRAFER, back.style());
    }
}
