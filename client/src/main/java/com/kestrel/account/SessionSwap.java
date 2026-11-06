package com.kestrel.account;

import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Replaces the running game's Minecraft session with one the launcher just
 * obtained through Microsoft sign-in.
 *
 * <p>Minecraft 1.8.9 keeps the session in a single final field of
 * {@link Minecraft} and reads it through {@code getSession()} whenever it
 * needs it: joining a server ({@code NetHandlerLoginClient} calls
 * {@code joinServer} with the current session), the name and skin shown, and
 * the cached profile properties ({@code getProfileProperties()}, refilled
 * from the session's profile when empty). Nothing else holds a copy, so
 * while no world is loaded the session can be swapped and the next server
 * joined uses the new account. The fields are found by type, so this works
 * with any mappings.
 *
 * <p>The swap happens only on the title screen (no world) and only with a
 * complete, validated session; on any failure the previous session is put
 * back, so the game never ends up half switched.
 */
public final class SessionSwap {
    private SessionSwap() {
    }

    /** A session from the launcher, after validation. */
    public static final class NewSession {
        public final String name;
        public final String uuid;
        final String token;

        NewSession(String name, String uuid, String token) {
            this.name = name;
            this.uuid = uuid;
            this.token = token;
        }
    }

    /** Validate the launcher's reply fields; null if anything is off. */
    public static NewSession validate(String name, String uuid, String token) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return null;
        if (uuid == null || !uuid.matches("[0-9a-f]{32}")) return null;
        if (token == null || token.length() < 20 || token.length() > 8192) return null;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i)) || Character.isISOControl(token.charAt(i))) return null;
        }
        return new NewSession(name, uuid, token);
    }

    private static Field onlyField(Class<?> owner, Class<?> type) {
        Field found = null;
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() == type && !Modifier.isStatic(f.getModifiers())) {
                if (found != null) throw new IllegalStateException("several " + type.getSimpleName() + " fields");
                found = f;
            }
        }
        if (found == null) throw new IllegalStateException("no " + type.getSimpleName() + " field");
        found.setAccessible(true);
        return found;
    }

    /**
     * Swap the session. Returns the previous one. Throws (leaving the game
     * unchanged) when a world is loaded or the session can't be replaced.
     */
    public static Session apply(Minecraft mc, NewSession s) {
        if (mc.theWorld != null) throw new IllegalStateException("leave the world first");
        Field sessionField = onlyField(Minecraft.class, Session.class);
        Field propsField = onlyField(Minecraft.class, PropertyMap.class);
        Session old;
        try {
            old = (Session) sessionField.get(mc);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        // Same user type the launcher passes on the command line for Microsoft accounts.
        Session next = new Session(s.name, s.uuid, s.token, "msa");
        try {
            sessionField.set(mc, next);
            if (mc.getSession() != next) throw new IllegalStateException("session did not change");
            // Profile properties (skin data) belong to the old account; they are refilled on demand.
            ((PropertyMap) propsField.get(mc)).clear();
        } catch (Exception e) {
            try {
                sessionField.set(mc, old);
            } catch (IllegalAccessException ignored) {
                // Unreachable: the same field was just written.
            }
            throw new IllegalStateException("could not switch the session: " + e, e);
        }
        return old;
    }
}
