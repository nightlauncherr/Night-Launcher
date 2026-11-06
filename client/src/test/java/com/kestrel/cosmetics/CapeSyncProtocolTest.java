package com.kestrel.cosmetics;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CapeSyncProtocolTest {
    private static final UUID ONLINE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"); // version 4
    private static final UUID OFFLINE = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes()); // version 3

    @Test
    public void onlyCatalogAndMojangCapesAreShared() {
        assertEquals("ember", CapeSyncProtocol.shareable(true, "ember"));
        assertEquals("mojang:minecon_2012", CapeSyncProtocol.shareable(true, "mojang:minecon_2012"));
        assertEquals("none", CapeSyncProtocol.shareable(true, "custom:my cape.png"));
        assertEquals("none", CapeSyncProtocol.shareable(false, "ember"));
        assertEquals("none", CapeSyncProtocol.shareable(true, null));
        assertEquals("none", CapeSyncProtocol.shareable(true, "../../etc"));
        assertEquals("none", CapeSyncProtocol.shareable(true, "none"));
    }

    @Test
    public void serverIdMatchesTheService() {
        // Same input as services/cosmetics: sha1("night-cape-v1|uuid|cape|ts|nonce").
        String id = CapeSyncProtocol.serverId("0123456789abcdef0123456789abcdef", "ember", 1800000000L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals(40, id.length());
        assertEquals(id, CapeSyncProtocol.serverId("0123456789abcdef0123456789abcdef", "ember", 1800000000L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertFalse(id.equals(CapeSyncProtocol.serverId("0123456789abcdef0123456789abcdef", "glacier",
                1800000000L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        assertEquals("8535a2580d22a544ba878513c8d0bf8f316e1a22", id); // same value pinned in services/cosmetics tests
    }

    @Test
    public void onlyOnlineAccountsAreLookedUp() {
        assertTrue(CapeSyncProtocol.lookupWorthy(ONLINE));
        assertFalse(CapeSyncProtocol.lookupWorthy(OFFLINE));
        assertFalse(CapeSyncProtocol.lookupWorthy(null));
    }

    @Test
    public void lookupQueryAndReplyRoundTrip() {
        assertEquals("069a79f444e94726a5befca90e38aaf5",
                CapeSyncProtocol.lookupQuery(Arrays.asList(ONLINE)));
        Map<UUID, String> m = CapeSyncProtocol.parseLookup(
                "{\"capes\":{\"069a79f444e94726a5befca90e38aaf5\":\"mojang:minecon_2012\","
                        + "\"nothex\":\"ember\",\"0123456789abcdef0123456789abcdef\":\"../bad\"}}");
        assertEquals(1, m.size());
        assertEquals("mojang:minecon_2012", m.get(ONLINE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedReplyThrows() {
        CapeSyncProtocol.parseLookup("{\"nope\":1}");
    }

    @Test
    public void publishBodyHasTheServiceFields() {
        assertEquals("{\"name\":\"Steve\",\"cape\":\"ember\",\"ts\":1800000000,\"nonce\":\"ab\"}",
                CapeSyncProtocol.publishBody("Steve", "ember", 1800000000L, "ab"));
        assertNull(CapeSyncProtocol.parseUuid32("zz"));
    }
}
