package com.kestrel.cosmetics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The document below was signed by services/cosmetics (lib.js, WebCrypto) with a
 * test key (not the service's); the launcher's Rust tests open the same file.
 */
public class EntitlementsTest {
    private static final String UUID = "0123456789abcdef0123456789abcdef";

    private static String text(String path) throws Exception {
        return new String(GifDecoderTest.resource(path), StandardCharsets.UTF_8);
    }

    private static PublicKey testKey() throws Exception {
        return Entitlements.parseKey("# test key\n" + text("/entitlements/entitlement-test-key.spki.b64"));
    }

    @Test
    public void aDocumentSignedByTheServiceOpensForItsAccount() throws Exception {
        String doc = text("/entitlements/entitlement-worker-doc.json");
        Set<String> r = Entitlements.open(doc, "01234567-89ab-cdef-0123-456789ABCDEF", testKey());
        assertEquals(Collections.singleton(Entitlements.OFFICIAL_CAPES), r);
    }

    @Test
    public void anotherAccountCannotUseIt() throws Exception {
        String doc = text("/entitlements/entitlement-worker-doc.json");
        try {
            Entitlements.open(doc, "fedcba9876543210fedcba9876543210", testKey());
            fail("opened for another account");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("another account"));
        }
    }

    @Test
    public void editingTheDocumentBreaksIt() throws Exception {
        JsonObject doc = new JsonParser().parse(text("/entitlements/entitlement-worker-doc.json")).getAsJsonObject();
        String payload = new String(Base64.getDecoder().decode(doc.get("payload").getAsString()), StandardCharsets.UTF_8);
        // Put another account in the payload, keep the signature: refused.
        String other = payload.replace(UUID, "fedcba9876543210fedcba9876543210");
        doc.addProperty("payload", Base64.getEncoder().encodeToString(other.getBytes(StandardCharsets.UTF_8)));
        try {
            Entitlements.open(doc.toString(), "fedcba9876543210fedcba9876543210", testKey());
            fail("edited document accepted");
        } catch (GeneralSecurityException expected) {
            // the signature no longer matches
        }
        for (String bad : new String[] {"{}", "not json", "{\"payload\":\"!!\",\"signature\":\"AA==\"}"}) {
            try {
                Entitlements.open(bad, UUID, testKey());
                fail("accepted " + bad);
            } catch (GeneralSecurityException expected) {
                // malformed
            }
        }
        try {
            Entitlements.open(text("/entitlements/entitlement-worker-doc.json"), UUID, null);
            fail("opened without a key");
        } catch (GeneralSecurityException expected) {
            // no key
        }
    }

    @Test
    public void bringingHasTheOfficialCapesBuiltIn() {
        assertTrue(Entitlements.builtin("ba14933e-a7ad-4816-8696-a788cc1b3c1f").contains(Entitlements.OFFICIAL_CAPES));
        assertTrue(Entitlements.builtin("BA14933EA7AD48168696A788CC1B3C1F").contains(Entitlements.OFFICIAL_CAPES));
        assertTrue(Entitlements.builtin(UUID).isEmpty());
        assertTrue(Entitlements.builtin("bringing").isEmpty());
        assertTrue(Entitlements.builtin(null).isEmpty());
    }

    @Test
    public void keyFilesParse() throws Exception {
        assertNull(Entitlements.parseKey("# only comments\n\n"));
        assertNotNull(testKey());
        // The bundled key file must at least parse (it is empty only in builds that can't redeem).
        Entitlements.parseKey(text(Entitlements.KEY_RESOURCE));
    }
}
