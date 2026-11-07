package com.kestrel.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cosmetic rewards of an account (codes redeemed with the Night cosmetics
 * service, services/cosmetics, plus a few built in). The service signs each
 * account's entitlement document with a key only it has; this checks the
 * signature with the bundled public key and that the document is for the
 * account asking, so an edited or copied cache file unlocks nothing. Pure
 * Java, unit-tested; must stay in step with services/cosmetics/src/lib.js
 * and the launcher's kestrel_core::entitlements.
 */
public final class Entitlements {
    public static final String PROTOCOL = "night-entitlements-v1";
    /** Reward id of the complete official Minecraft cape collection. */
    public static final String OFFICIAL_CAPES = "official_capes";
    static final String KEY_RESOURCE = "/assets/kestrel/entitlement_public_key.txt";

    /** Accounts with rewards they didn't redeem (32-hex UUID). Same list as the service and launcher. */
    private static final Map<String, Set<String>> BUILTIN;

    static {
        Map<String, Set<String>> m = new HashMap<String, Set<String>>();
        // bringing
        m.put("ba14933ea7ad48168696a788cc1b3c1f", Collections.singleton(OFFICIAL_CAPES));
        BUILTIN = Collections.unmodifiableMap(m);
    }

    private Entitlements() {
    }

    /** Built-in rewards of an account (any UUID format; empty for null or unknown). */
    public static Set<String> builtin(String uuid) {
        String u = CapeCatalog.normalizeUuid(uuid);
        Set<String> r = u == null ? null : BUILTIN.get(u);
        return r == null ? Collections.<String>emptySet() : r;
    }

    /**
     * The public key in a key file: base64 SubjectPublicKeyInfo, lines starting
     * with '#' ignored. Null when the file holds no key.
     */
    public static PublicKey parseKey(String text) throws GeneralSecurityException {
        StringBuilder b64 = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-----")) continue;
            b64.append(t);
        }
        if (b64.length() == 0) return null;
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64.toString());
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("bad base64 in the entitlement key", e);
        }
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** The bundled key, or null when this build has none (or it is unreadable). */
    public static PublicKey bundledKey() {
        try (InputStream in = Entitlements.class.getResourceAsStream(KEY_RESOURCE)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            return parseKey(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | GeneralSecurityException e) {
            return null;
        }
    }

    /**
     * Check a signed document ({@code {"payload","signature"}}) and return the
     * reward ids of {@code uuid} it grants. Throws when the signature, format
     * or account doesn't match.
     */
    public static Set<String> open(String docJson, String uuid, PublicKey key) throws GeneralSecurityException {
        String want = CapeCatalog.normalizeUuid(uuid);
        if (want == null) throw new GeneralSecurityException("invalid account id");
        if (key == null) throw new GeneralSecurityException("no entitlement key");
        byte[] payload;
        byte[] sig;
        try {
            JsonObject doc = new JsonParser().parse(docJson).getAsJsonObject();
            payload = Base64.getDecoder().decode(doc.get("payload").getAsString().trim());
            sig = Base64.getDecoder().decode(doc.get("signature").getAsString().trim());
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("malformed entitlement document", e);
        }
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(key);
        s.update(payload);
        if (!s.verify(sig)) throw new GeneralSecurityException("the signature does not match");
        try {
            JsonObject p = new JsonParser().parse(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            if (p.get("v").getAsInt() != 1 || !PROTOCOL.equals(p.get("protocol").getAsString())) {
                throw new GeneralSecurityException("unsupported entitlement document");
            }
            if (!want.equals(CapeCatalog.normalizeUuid(p.get("uuid").getAsString()))) {
                throw new GeneralSecurityException("the document is for another account");
            }
            Set<String> rewards = new HashSet<String>();
            JsonElement r = p.get("rewards");
            if (r != null && r.isJsonArray()) {
                JsonArray a = r.getAsJsonArray();
                for (JsonElement e : a) {
                    if (e.isJsonPrimitive()) rewards.add(e.getAsString());
                }
            }
            return rewards;
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("malformed entitlement payload", e);
        }
    }
}
