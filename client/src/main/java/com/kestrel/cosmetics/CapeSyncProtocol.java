package com.kestrel.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Wire format of the Night cosmetics service (services/cosmetics): which cape
 * ids may be shared, the signed publish request that proves account ownership
 * (with the account's Mojang player certificate), and parsing of lookup
 * replies. Pure Java, unit-tested; must stay in step with
 * services/cosmetics/src/lib.js.
 */
public final class CapeSyncProtocol {
    public static final String PROTOCOL = "night-cape-v1";
    public static final String NONE = "none";
    public static final int MAX_LOOKUP = 100;
    private static final Pattern CAPE = Pattern.compile("none|[a-z0-9_]{1,48}|mojang:[a-z0-9_]{1,48}");

    private CapeSyncProtocol() {
    }

    /**
     * The cape id to publish for a selection: catalog and Mojang capes as is;
     * uploaded (custom:) capes are local images and are never shared, so they
     * publish as {@code none}, as do hidden or unset capes.
     */
    public static String shareable(boolean enabled, String selection) {
        if (!enabled || selection == null) return NONE;
        if (selection.startsWith(CapeManager.CUSTOM_PREFIX)) return NONE;
        return CAPE.matcher(selection).matches() ? selection : NONE;
    }

    /** Whether a cape id received from the service is well-formed (unknown ids are ignored later). */
    public static boolean validId(String id) {
        return id != null && !NONE.equals(id) && CAPE.matcher(id).matches();
    }

    /** UUID as 32 lowercase hex digits, as the service and Mojang use. */
    public static String uuid32(UUID id) {
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Only real (online) accounts can have published a cape: Microsoft/Mojang
     * account UUIDs are version 4; offline-mode and NPC UUIDs are not.
     */
    public static boolean lookupWorthy(UUID id) {
        return id != null && id.version() == 4;
    }

    /**
     * The text the player's certificate key signs to publish a cape. Must match
     * signedText in services/cosmetics/src/lib.js.
     */
    public static String signedText(String uuid32, String cape, long ts, String nonce) {
        return PROTOCOL + "|" + uuid32 + "|" + cape + "|" + ts + "|" + nonce;
    }

    /**
     * An account's Mojang player certificate (the key pair Minecraft 1.19+
     * signs chat with), as returned by
     * {@code POST https://api.minecraftservices.com/player/certificates}.
     */
    public static final class PlayerCertificate {
        /** PKCS#8 DER. Stays on this PC. */
        public final byte[] privateKey;
        /** X.509 SubjectPublicKeyInfo DER. */
        public final byte[] publicKey;
        /** Mojang's signature binding publicKey to the account UUID and expiry ("publicKeySignatureV2"). */
        public final byte[] mojangSignature;
        public final long expiresAtMs;

        PlayerCertificate(byte[] privateKey, byte[] publicKey, byte[] mojangSignature, long expiresAtMs) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.mojangSignature = mojangSignature;
            this.expiresAtMs = expiresAtMs;
        }

        /** Whether it is still good for at least {@code marginMs}. */
        public boolean usableAt(long nowMs, long marginMs) {
            return expiresAtMs - marginMs > nowMs;
        }
    }

    /** Parse Mojang's player-certificate reply; throws IllegalArgumentException when malformed. */
    public static PlayerCertificate parseCertificate(String json) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("certificate reply is not an object");
        JsonObject o = root.getAsJsonObject();
        JsonElement kp = o.get("keyPair");
        if (kp == null || !kp.isJsonObject()) throw new IllegalArgumentException("certificate reply has no keyPair");
        byte[] priv = pem(string(kp.getAsJsonObject(), "privateKey"));
        byte[] pub = pem(string(kp.getAsJsonObject(), "publicKey"));
        byte[] sig = base64(string(o, "publicKeySignatureV2"));
        long expires;
        try {
            expires = Instant.parse(string(o, "expiresAt")).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("certificate reply has a bad expiresAt", e);
        }
        return new PlayerCertificate(priv, pub, sig, expires);
    }

    /** Sign {@link #signedText} with the certificate's private key (SHA256withRSA). */
    public static byte[] sign(PlayerCertificate cert, String text) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(cert.privateKey));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(text.getBytes(StandardCharsets.UTF_8));
        return s.sign();
    }

    private static String string(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive()) throw new IllegalArgumentException("certificate reply has no " + name);
        return e.getAsString();
    }

    /** The DER inside a PEM block (any label; Mojang labels PKCS#8 keys "RSA PRIVATE KEY"). */
    static byte[] pem(String text) {
        StringBuilder b64 = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("-----")) continue;
            b64.append(t);
        }
        return base64(b64.toString());
    }

    private static byte[] base64(String s) {
        try {
            byte[] d = Base64.getDecoder().decode(s);
            if (d.length == 0) throw new IllegalArgumentException("empty key data");
            return d;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base64 in certificate reply", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Request body for publishing a cape (see services/cosmetics/src/lib.js). */
    public static String publishBody(String cape, long ts, String nonce, PlayerCertificate cert, byte[] signature) {
        Base64.Encoder b64 = Base64.getEncoder();
        JsonObject key = new JsonObject();
        key.addProperty("publicKey", b64.encodeToString(cert.publicKey));
        key.addProperty("expiresAt", cert.expiresAtMs);
        key.addProperty("signature", b64.encodeToString(cert.mojangSignature));
        JsonObject o = new JsonObject();
        o.addProperty("cape", cape);
        o.addProperty("ts", ts);
        o.addProperty("nonce", nonce);
        o.add("key", key);
        o.addProperty("signature", b64.encodeToString(signature));
        return o.toString();
    }

    /** Query string value for a lookup of these players. */
    public static String lookupQuery(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(uuid32(id));
        }
        return sb.toString();
    }

    /**
     * Parse a lookup reply: {@code {"capes": {"<uuid32>": "<id>"}}}. Entries
     * with malformed UUIDs or ids are dropped; a malformed reply throws.
     */
    public static Map<UUID, String> parseLookup(String json) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("reply is not an object");
        JsonElement capes = root.getAsJsonObject().get("capes");
        if (capes == null || !capes.isJsonObject()) throw new IllegalArgumentException("reply has no capes object");
        Map<UUID, String> out = new HashMap<UUID, String>();
        for (Map.Entry<String, JsonElement> e : capes.getAsJsonObject().entrySet()) {
            UUID id = parseUuid32(e.getKey());
            JsonElement v = e.getValue();
            if (id == null || v == null || !v.isJsonPrimitive()) continue;
            String cape = v.getAsString();
            if (validId(cape)) out.put(id, cape);
        }
        return out;
    }

    static UUID parseUuid32(String s) {
        if (s == null || s.length() != 32) return null;
        for (int i = 0; i < 32; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return null;
        }
        String d = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                + s.substring(16, 20) + "-" + s.substring(20);
        try {
            return UUID.fromString(d);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
