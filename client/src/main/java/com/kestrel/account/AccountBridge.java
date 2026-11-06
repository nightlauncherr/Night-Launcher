package com.kestrel.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kestrel.KestrelMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The game's channel to the Night Launcher that started it, used by the
 * in-game account switcher.
 *
 * <p>Requests are log lines {@code [Kestrel] request <token> <n> <verb> [arg]}
 * (the launcher reads the game's output; the per-launch token from
 * {@code -Dkestrel.activityToken} keeps chat from spoofing them). Replies arrive
 * on stdin, one JSON object per line tagged {@code "re": n}. Only the
 * launcher that spawned this process can write to its stdin. When the
 * launcher is gone (closed after launch, or the game was started some other
 * way) stdin ends and {@link #available()} turns false.
 *
 * <p>Threading: a daemon thread reads stdin; everything else, including
 * listener callbacks, runs on the game thread via {@link #tick()}.
 */
public final class AccountBridge {
    public static final String MARKER = "[Kestrel] request ";
    private static final int MAX_LINE = 64 * 1024;

    public interface Listener {
        /** A reply to this listener's request (game thread). */
        void onReply(JsonObject reply);
    }

    private final String token;
    private final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<JsonObject>();
    private final Map<Integer, Listener> listeners = new HashMap<Integer, Listener>();
    private volatile boolean closed;
    private int next = 1;

    public AccountBridge(String token) {
        this.token = token != null && token.matches("[0-9a-f]{32}") ? token : null;
    }

    /** Start reading replies. Does nothing without a launcher token. */
    public void start() {
        if (token == null) {
            closed = true;
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                read();
            }
        }, "Night-LauncherBridge");
        t.setDaemon(true);
        t.start();
    }

    private void read() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject o = parse(line);
                if (o != null) inbox.add(o);
            }
        } catch (IOException e) {
            KestrelMod.LOG.debug("[Kestrel] launcher channel closed: {}", e.toString());
        } finally {
            closed = true;
        }
    }

    /** A reply line as a JSON object with a numeric {@code re}, or null. */
    static JsonObject parse(String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE) return null;
        try {
            JsonElement e = new JsonParser().parse(line);
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            JsonElement re = o.get("re");
            if (re == null || !re.isJsonPrimitive() || !re.getAsJsonPrimitive().isNumber()) return null;
            return o;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The request line for {@code n}; pure, for tests. */
    static String requestLine(String token, int n, String verb, String arg) {
        return MARKER + token + " " + n + " " + verb + (arg == null ? "" : " " + arg);
    }

    /** Whether a launcher is listening. */
    public boolean available() {
        return token != null && !closed;
    }

    /**
     * Ask the launcher. Returns the request number, or -1 when no launcher is
     * listening (the listener is then never called).
     */
    public int request(String verb, String arg, Listener listener) {
        if (!available()) return -1;
        int n = next++;
        listeners.put(n, listener);
        KestrelMod.LOG.info(requestLine(token, n, verb, arg));
        return n;
    }

    /** Stop delivering replies for request {@code n} (its screen closed). */
    public void forget(int n) {
        listeners.remove(n);
    }

    /** Deliver replies (game thread, once per tick). */
    public void tick() {
        JsonObject o;
        while ((o = inbox.poll()) != null) {
            int re = o.get("re").getAsInt();
            Listener l = listeners.get(re);
            if (l == null) continue;
            // "code" is a progress update; every other reply ends the request.
            JsonElement type = o.get("type");
            if (type == null || !"code".equals(type.getAsString())) listeners.remove(re);
            try {
                l.onReply(o);
            } catch (RuntimeException e) {
                KestrelMod.LOG.warn("[Kestrel] account switcher reply failed: {}", e.toString());
            }
        }
    }
}
