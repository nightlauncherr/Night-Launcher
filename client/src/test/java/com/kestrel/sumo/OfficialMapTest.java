package com.kestrel.sumo;

import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OfficialMapTest {
    @Test
    public void officialWorldIsShippedComplete() throws Exception {
        Set<String> names = new HashSet<String>();
        String marker = null;
        try (InputStream in = OfficialMapTest.class.getResourceAsStream("/assets/kestrel/sumo/official-world.zip")) {
            assertNotNull("official map missing from the jar", in);
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                names.add(e.getName());
                assertFalse("unsafe entry " + e.getName(), e.getName().contains("..") || e.getName().startsWith("/"));
                if (e.getName().equals("night-official-map.json")) {
                    StringBuilder b = new StringBuilder();
                    InputStreamReader r = new InputStreamReader(zip, StandardCharsets.UTF_8);
                    for (int c; (c = r.read()) != -1; ) b.append((char) c);
                    marker = b.toString();
                }
            }
        }
        assertTrue(names.contains("level.dat"));
        assertTrue(names.contains("region/r.0.0.mca"));
        assertTrue(names.contains("region/r.0.-1.mca"));
        assertTrue(names.contains("region/r.-1.-1.mca"));
        assertTrue(names.contains("region/r.-1.0.mca"));
        for (String n : names) assertFalse("player data must not ship: " + n, n.startsWith("playerdata/") || n.startsWith("stats/"));
        assertNotNull(marker);
        assertTrue(marker.contains("\"version\": 1"));
    }

    @Test
    public void officialLayoutCoversTheWholeMap() throws Exception {
        try (InputStream in = OfficialMapTest.class.getResourceAsStream("/assets/kestrel/sumo/official-arena.json")) {
            assertNotNull(in);
            ArenaLayout l = new com.google.gson.Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ArenaLayout.class);
            l.normalize();
            assertEquals(-3.0, l.playerSpawn.x, 1e-9);
            assertEquals(4.0, l.aiSpawn.x, 1e-9);
            assertEquals(-27.0, l.lobbySpawn.z, 1e-9);
            // The built lobby reaches z = -39; the save area must include all of it.
            assertTrue(l.regionContains(-10, 98, -39));
            assertTrue(l.regionContains(10, 103, 7));
        }
    }

    @Test
    public void onlyTheOwnerUuidMayEdit() {
        assertTrue(SumoPermissions.canEdit(UUID.fromString("ba14933e-a7ad-4816-8696-a788cc1b3c1f")));
        // Same name, offline account: different UUID, no access.
        assertFalse(SumoPermissions.canEdit(UUID.nameUUIDFromBytes("OfflinePlayer:bringing".getBytes(StandardCharsets.UTF_8))));
        assertFalse(SumoPermissions.canEdit(UUID.randomUUID()));
        assertFalse(SumoPermissions.canEdit((UUID) null));
    }
}
