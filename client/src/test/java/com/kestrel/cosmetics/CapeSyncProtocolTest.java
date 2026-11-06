package com.kestrel.cosmetics;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
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
    public void signedTextMatchesTheService() {
        // Same value pinned in services/cosmetics/test/index.test.js.
        assertEquals("night-cape-v1|0123456789abcdef0123456789abcdef|ember|1800000000|"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                CapeSyncProtocol.signedText("0123456789abcdef0123456789abcdef", "ember", 1800000000L,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    /** A reply shaped like Mojang's (PKCS#8 under an "RSA PRIVATE KEY" label, microsecond expiry). */
    private static String mojangReply(KeyPair kp, String expiresAt) {
        Base64.Encoder mime = Base64.getMimeEncoder(76, "\n".getBytes());
        return "{\"keyPair\":{\"privateKey\":\"-----BEGIN RSA PRIVATE KEY-----\\n"
                + mime.encodeToString(kp.getPrivate().getEncoded()).replace("\n", "\\n")
                + "\\n-----END RSA PRIVATE KEY-----\\n\",\"publicKey\":\"-----BEGIN RSA PUBLIC KEY-----\\n"
                + mime.encodeToString(kp.getPublic().getEncoded()).replace("\n", "\\n")
                + "\\n-----END RSA PUBLIC KEY-----\\n\"},\"publicKeySignature\":\"AAAA\","
                + "\"publicKeySignatureV2\":\"3q2+7w==\",\"expiresAt\":\"" + expiresAt + "\","
                + "\"refreshedAfter\":\"2026-11-07T10:00:00.123456Z\"}";
    }

    @Test
    public void parsesMojangCertificatesAndSignsWithThem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        CapeSyncProtocol.PlayerCertificate cert =
                CapeSyncProtocol.parseCertificate(mojangReply(kp, "2026-11-08T12:00:00.402874Z"));
        assertArrayEquals(kp.getPublic().getEncoded(), cert.publicKey);
        assertArrayEquals(kp.getPrivate().getEncoded(), cert.privateKey);
        assertArrayEquals(new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}, cert.mojangSignature);
        assertEquals(Instant.parse("2026-11-08T12:00:00.402Z").toEpochMilli(), cert.expiresAtMs);
        assertTrue(cert.usableAt(cert.expiresAtMs - 20 * 60_000L, 10 * 60_000L));
        assertFalse(cert.usableAt(cert.expiresAtMs - 5 * 60_000L, 10 * 60_000L));

        String text = CapeSyncProtocol.signedText("0123456789abcdef0123456789abcdef", "ember", 1800000000L, "ab");
        byte[] sig = CapeSyncProtocol.sign(cert, text);
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(kp.getPublic());
        v.update(text.getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(sig));
    }

    @Test(expected = IllegalArgumentException.class)
    public void certificateWithoutKeysIsRejected() {
        CapeSyncProtocol.parseCertificate("{\"publicKeySignatureV2\":\"AAAA\",\"expiresAt\":\"2026-11-08T12:00:00Z\"}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void certificateWithBadExpiryIsRejected() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        CapeSyncProtocol.parseCertificate(mojangReply(g.generateKeyPair(), "tomorrow"));
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
        CapeSyncProtocol.PlayerCertificate cert = new CapeSyncProtocol.PlayerCertificate(
                new byte[] {9}, new byte[] {1, 2, 3}, new byte[] {4, 5}, 1800000000123L);
        assertEquals("{\"cape\":\"ember\",\"ts\":1800000000,\"nonce\":\"ab\",\"key\":{\"publicKey\":\"AQID\","
                        + "\"expiresAt\":1800000000123,\"signature\":\"BAU=\"},\"signature\":\"Bwg=\"}",
                CapeSyncProtocol.publishBody("ember", 1800000000L, "ab", cert, new byte[] {7, 8}));
        assertNull(CapeSyncProtocol.parseUuid32("zz"));
    }
}
