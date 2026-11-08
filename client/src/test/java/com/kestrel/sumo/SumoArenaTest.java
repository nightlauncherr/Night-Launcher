package com.kestrel.sumo;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SumoArenaTest {
    @Test
    public void startPositionsAreOnOppositeSidesFacingEachOther() {
        double[] p = SumoArena.playerSpawn();
        double[] b = SumoArena.botSpawn();
        assertEquals(2 * SumoArena.SPAWN_OFFSET, b[0] - p[0], 1e-9);
        assertEquals(p[2], b[2], 1e-9);
        assertTrue(SumoArena.edgeDistance(p[0], p[2]) > 3);
        assertTrue(SumoArena.edgeDistance(b[0], b[2]) > 3);
        // yaw -90 looks towards +X (the bot), 90 towards -X (the player).
        assertEquals(1.0, -Math.sin(Math.toRadians(p[3])), 1e-9);
        assertEquals(-1.0, -Math.sin(Math.toRadians(b[3])), 1e-9);
        assertEquals(SumoArena.SURFACE_Y, p[1], 1e-9);
    }

    @Test
    public void platformIsFifteenSquareCenteredOnTheMiddleBlock() {
        assertEquals(15, SumoArena.MAX - SumoArena.MIN + 1);
        assertEquals(0.5, SumoArena.CENTER_X, 1e-9);
        assertEquals(7.5, SumoArena.edgeDistance(SumoArena.CENTER_X, SumoArena.CENTER_Z), 1e-9);
    }

    @Test
    public void edgesNormalsAndFalls() {
        assertEquals(0.5, SumoArena.edgeDistance(SumoArena.maxEdge() - 0.5, 0.5), 1e-9);
        assertTrue(SumoArena.overPlatform(SumoArena.maxEdge() - 0.01, 0.5));
        assertFalse(SumoArena.overPlatform(SumoArena.maxEdge() + 0.01, 0.5));
        assertArrayEquals(new double[]{1, 0}, SumoArena.nearestEdgeNormal(7.9, 0.5), 1e-9);
        assertArrayEquals(new double[]{-1, 0}, SumoArena.nearestEdgeNormal(-6.9, 0.5), 1e-9);
        assertArrayEquals(new double[]{0, 1}, SumoArena.nearestEdgeNormal(0.5, 7.8), 1e-9);
        assertArrayEquals(new double[]{0, -1}, SumoArena.nearestEdgeNormal(0.5, -6.8), 1e-9);
        assertFalse(SumoArena.fell(SumoArena.SURFACE_Y));
        assertFalse(SumoArena.fell(SumoArena.SURFACE_Y - 1.5)); // still could be standing in a hole edge case
        assertTrue(SumoArena.fell(SumoArena.SURFACE_Y - 3.5));
    }

    @Test
    public void voidWorldPresetHasOnlyAir() {
        assertEquals("3;minecraft:air;1;", SumoArena.GENERATOR);
        assertTrue(SumoArena.WORLD_FOLDER.matches("[a-z0-9-]+"));
    }
}
