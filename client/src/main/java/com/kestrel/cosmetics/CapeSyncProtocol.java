package com.kestrel.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Wire format of the Night cosmetics service (services/cosmetics): which cape
 * ids may be shared, the Mojang "join" server id that proves account
 * ownership, and parsing of lookup replies. Pure Java, unit-tested; must stay
 * in step with services/cosmetics/src/index.js.
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

    /** The serverId the client joins with and the service checks with hasJoined. */
    public static String serverId(String uuid32, String cape, long ts, String nonce) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((PROTOCOL + "|" + uuid32 + "|" + cape + "|" + ts + "|" + nonce)
                    .getBytes(StandardCharsets.UTF_8));
            return hex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Request body for publishing a cape. */
    public static String publishBody(String name, String cape, long ts, String nonce) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("cape", cape);
        o.addProperty("ts", ts);
        o.addProperty("nonce", nonce);
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
