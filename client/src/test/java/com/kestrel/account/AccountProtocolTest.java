package com.kestrel.account;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountProtocolTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    public void requestLinesMatchTheLauncherParser() {
        // Same shapes as parse_request's tests in launcher/crates/kestrel-core/src/launch.rs.
        assertEquals("[Kestrel] request " + TOKEN + " 1 accounts", AccountBridge.requestLine(TOKEN, 1, "accounts", null));
        assertEquals("[Kestrel] request " + TOKEN + " 2 switch ba14933ea7ad48168696a788cc1b3c1f",
                AccountBridge.requestLine(TOKEN, 2, "switch", "ba14933ea7ad48168696a788cc1b3c1f"));
        assertEquals("[Kestrel] request " + TOKEN + " 8 cancel 7", AccountBridge.requestLine(TOKEN, 8, "cancel", "7"));
    }

    @Test
    public void bridgeWithoutTokenIsUnavailable() {
        assertFalse(new AccountBridge(null).available());
        assertFalse(new AccountBridge("not-a-token").available());
        AccountBridge b = new AccountBridge(null);
        b.start();
        assertEquals(-1, b.request("accounts", null, r -> { }));
        assertTrue(new AccountBridge(TOKEN).available());
    }

    @Test
    public void repliesNeedANumericRe() {
        JsonObject o = AccountBridge.parse("{\"re\":3,\"type\":\"accounts\",\"accounts\":[]}");
        assertNotNull(o);
        assertEquals(3, o.get("re").getAsInt());
        assertNull(AccountBridge.parse("{\"type\":\"session\"}"));
        assertNull(AccountBridge.parse("{\"re\":\"x\"}"));
        assertNull(AccountBridge.parse("[1,2]"));
        assertNull(AccountBridge.parse("not json"));
        assertNull(AccountBridge.parse(""));
        assertNull(AccountBridge.parse(null));
    }

    @Test
    public void sessionsAreValidatedBeforeAnySwap() {
        String uuid = "ba14933ea7ad48168696a788cc1b3c1f";
        String token = "eyJhbGciOiJIUzI1NiJ9.payload.signature";
        SessionSwap.NewSession s = SessionSwap.validate("Bringing", uuid, token);
        assertNotNull(s);
        assertEquals("Bringing", s.name);
        assertEquals(uuid, s.uuid);
        assertNull(SessionSwap.validate("bad name!", uuid, token));
        assertNull(SessionSwap.validate("Bringing", "BA14933EA7AD48168696A788CC1B3C1F", token));
        assertNull(SessionSwap.validate("Bringing", uuid.substring(1), token));
        assertNull(SessionSwap.validate("Bringing", uuid, "short"));
        assertNull(SessionSwap.validate("Bringing", uuid, token + "\nextra"));
        assertNull(SessionSwap.validate("Bringing", uuid, null));
        assertNull(SessionSwap.validate(null, uuid, token));
    }
}
