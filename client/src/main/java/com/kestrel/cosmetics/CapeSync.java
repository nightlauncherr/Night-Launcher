package com.kestrel.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.kestrel.KestrelClient;
import com.kestrel.KestrelMod;
import com.kestrel.config.ClientConfig;
import com.kestrel.util.NightTls;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shares equipped Night capes between Night players through the Night
 * cosmetics service (services/cosmetics).
 *
 * <p><b>Publishing</b>: when your shareable cape changes, your client gets
 * your account's Mojang player certificate (the key pair newer Minecraft
 * versions sign chat with; Mojang signs its public key for your UUID), signs
 * "cape, time, nonce" with it and sends that to the service, which checks
 * both signatures before storing the cape. So only you can set your cape, and
 * neither your Minecraft token nor the private key ever reaches the service.
 * Offline-mode sessions cannot publish.
 *
 * <p><b>Lookups</b>: the UUIDs of real accounts around you are looked up in
 * batches (at most 100 per request, at most one request in flight), cached
 * for five minutes, and their capes drawn like your own. Players without a
 * Night cape cost one cached "none" entry.
 *
 * <p>Game thread: {@link #tick()} and {@link #capeOf(UUID)}. Network: one
 * background thread. Disabled when the build has no service URL.
 */
public final class CapeSync {
    private static final String SERVICE_CONFIG = "/assets/kestrel/cosmetics_service.json";
    private static final long LOOKUP_TTL_MS = 5 * 60 * 1000L;
    private static final long LOOKUP_INTERVAL_MS = 2000L;
    private static final long FORGET_AFTER_MS = 30 * 60 * 1000L;
    private static final long PUBLISH_DEBOUNCE_MS = 3000L;
    private static final long PUBLISH_RETRY_MS = 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_REPLY = 64 * 1024;
    private static final String CERTIFICATE_URL = "https://api.minecraftservices.com/player/certificates";
    private static final long CERTIFICATE_MARGIN_MS = 10 * 60 * 1000L;
    /** Cached "no cape" marker. */
    private static final String NO_CAPE = "";

    private final KestrelClient client;
    private final String baseUrl;
    private final ExecutorService net;
    private final SecureRandom random = new SecureRandom();

    private static final class Known {
        final String cape; // NO_CAPE when the player has none
        final long fetchedMs;

        Known(String cape, long fetchedMs) {
            this.cape = cape;
            this.fetchedMs = fetchedMs;
        }
    }

    private final Map<UUID, Known> known = new ConcurrentHashMap<UUID, Known>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean lookupRunning;
    private long lastLookupMs;
    private boolean lookupFailureLogged;

    // Network thread only.
    private CapeSyncProtocol.PlayerCertificate certificate;
    private String certificateOwner;

    private volatile String published; // last cape the service accepted for this session
    /** Bumped when the in-game account switcher changes the session. */
    private volatile int sessionGeneration;
    private volatile boolean publishRunning;
    private String wanted;
    private long wantedSinceMs;
    private long nextPublishMs;

    /** Whether the local account may share a cape id (official capes need the collection). */
    private volatile java.util.function.Predicate<String> mayShare = id -> true;

    public CapeSync(KestrelClient client) {
        this.client = client;
        this.baseUrl = loadServiceUrl();
        if (baseUrl == null) {
            this.net = null;
            KestrelMod.LOG.info("[Kestrel] cape sharing: no cosmetics service configured; capes stay local");
        } else {
            this.net = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Night-CapeSync");
                t.setDaemon(true);
                return t;
            });
            KestrelMod.LOG.info("[Kestrel] cape sharing via {}", baseUrl);
        }
    }

    public boolean enabled() {
        return net != null;
    }

    /** Set which cape ids the local account may share; others publish as none. */
    void setShareFilter(java.util.function.Predicate<String> filter) {
        this.mayShare = filter;
    }

    /** A player's shared cape id, or null (unknown, none, or sharing disabled). Game thread. */
    public String capeOf(UUID player) {
        if (net == null || player == null) return null;
        Known k = known.get(player);
        return k == null || NO_CAPE.equals(k.cape) ? null : k.cape;
    }

    /** Game thread, every client tick. Cheap when nothing is due. */
    public void tick() {
        if (net == null) return;
        long now = System.currentTimeMillis();
        publishIfChanged(now);
        lookupPlayersInView(now);
    }

    // ---- publishing ---------------------------------------------------------

    private void publishIfChanged(long now) {
        ClientConfig.Cosmetics c = client.config().cosmetics;
        String want = c.shareCape ? CapeSyncProtocol.shareable(c.capesEnabled, c.cape) : CapeSyncProtocol.NONE;
        // An official cape the account hasn't unlocked isn't drawn locally, so it isn't shared either
        // (the service would refuse it anyway).
        if (!CapeSyncProtocol.NONE.equals(want) && !mayShare.test(want)) want = CapeSyncProtocol.NONE;
        if (!want.equals(wanted)) {
            wanted = want;
            wantedSinceMs = now;
        }
        if (want.equals(published) || publishRunning || now < nextPublishMs) return;
        if (now - wantedSinceMs < PUBLISH_DEBOUNCE_MS) return; // let rapid clicking settle
        Minecraft mc = Minecraft.getMinecraft();
        final Session session = mc.getSession();
        if (session == null || !onlineSession(session)) {
            nextPublishMs = Long.MAX_VALUE; // offline mode: nothing to prove ownership with
            return;
        }
        publishRunning = true;
        final String cape = want;
        final int generation = sessionGeneration;
        net.execute(() -> {
            try {
                publish(session, cape);
                // A publish for the previous account must not count for the new one.
                if (generation == sessionGeneration) published = cape;
                nextPublishMs = 0;
                KestrelMod.LOG.info("[Kestrel] cape sharing: published {}", cape);
                // Read it back through the lookup path other players use (diagnostics).
                try {
                    UUID me = session.getProfile().getId();
                    String seen = lookup(java.util.Collections.singletonList(me)).get(me);
                    KestrelMod.LOG.info("[Kestrel] cape sharing: other Night players now see {}",
                            seen == null ? "no cape" : seen);
                } catch (Exception e) {
                    KestrelMod.LOG.warn("[Kestrel] cape sharing: published, but reading it back failed: {}",
                            e.toString());
                }
            } catch (Exception e) {
                nextPublishMs = System.currentTimeMillis() + PUBLISH_RETRY_MS;
                KestrelMod.LOG.warn("[Kestrel] cape sharing: could not publish {} (retrying in a minute): {}", cape,
                        e.toString());
            } finally {
                publishRunning = false;
            }
        });
    }

    /** The account changed: publish the cape again for the new one. Game thread. */
    void onSessionChanged() {
        sessionGeneration++;
        published = null;
        wanted = null;
        nextPublishMs = 0;
    }

    private static boolean onlineSession(Session s) {
        String token = s.getToken();
        String id = s.getPlayerID();
        if (token == null || token.length() < 20 || id == null) return false; // offline tokens are short
        UUID u = parseUuid(id);
        return u != null && CapeSyncProtocol.lookupWorthy(u);
    }

    private void publish(Session session, String cape) throws Exception {
        String uuid = CapeSyncProtocol.uuid32(session.getProfile().getId());
        CapeSyncProtocol.PlayerCertificate cert = certificate(session);
        long ts = System.currentTimeMillis() / 1000L;
        byte[] n = new byte[16];
        random.nextBytes(n);
        String nonce = CapeSyncProtocol.hex(n);
        // Signed with this account's Mojang-issued key; the private key and the login token never leave this PC.
        byte[] signature = CapeSyncProtocol.sign(cert, CapeSyncProtocol.signedText(uuid, cape, ts, nonce));
        byte[] body = CapeSyncProtocol.publishBody(cape, ts, nonce, cert, signature).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = NightTls.open(new URL(baseUrl + "/v1/capes/" + uuid));
        try {
            c.setRequestMethod("PUT");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Night-Client/" + client.version());
            c.setFixedLengthStreamingMode(body.length);
            OutputStream out = c.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int code = c.getResponseCode();
            if (code == 403) certificate = null; // fetch a fresh one next time
            if (code != 204 && code != 200) throw new IOException("service answered HTTP " + code + ": " + errorText(c));
        } finally {
            c.disconnect();
        }
    }

    /**
     * This account's Mojang player certificate, fetched from Mojang with the
     * session token (sent to Mojang only) and kept in memory while it is valid
     * (Mojang issues them for about two days). Network thread.
     */
    private CapeSyncProtocol.PlayerCertificate certificate(Session session) throws IOException {
        CapeSyncProtocol.PlayerCertificate cert = certificate;
        String owner = session.getPlayerID();
        if (cert != null && owner.equals(certificateOwner)
                && cert.usableAt(System.currentTimeMillis(), CERTIFICATE_MARGIN_MS)) {
            return cert;
        }
        HttpURLConnection c = NightTls.open(new URL(CERTIFICATE_URL));
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + session.getToken());
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Night-Client/" + client.version());
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Mojang player certificate: HTTP " + code + (code == 401
                    ? " (sign in again in the launcher)" : ""));
            cert = CapeSyncProtocol.parseCertificate(read(c.getInputStream()));
        } catch (IllegalArgumentException e) {
            throw new IOException("Mojang player certificate: " + e.getMessage(), e);
        } finally {
            c.disconnect();
        }
        if (!cert.usableAt(System.currentTimeMillis(), CERTIFICATE_MARGIN_MS)) {
            throw new IOException("Mojang player certificate is already expired (is the PC clock right?)");
        }
        certificate = cert;
        certificateOwner = owner;
        return cert;
    }

    // ---- lookups ------------------------------------------------------------

    private void lookupPlayersInView(long now) {
        if (lookupRunning || now - lastLookupMs < LOOKUP_INTERVAL_MS) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !client.config().cosmetics.showAssignedCapes) return;
        lastLookupMs = now;
        forgetOld(now);
        final List<UUID> batch = new ArrayList<UUID>();
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (p == mc.thePlayer) continue;
            UUID id = p.getUniqueID();
            if (!CapeSyncProtocol.lookupWorthy(id) || inFlight.contains(id)) continue;
            Known k = known.get(id);
            if (k != null && now - k.fetchedMs < LOOKUP_TTL_MS) continue;
            batch.add(id);
            if (batch.size() == CapeSyncProtocol.MAX_LOOKUP) break;
        }
        if (batch.isEmpty()) return;
        inFlight.addAll(batch);
        lookupRunning = true;
        net.execute(() -> {
            try {
                Map<UUID, String> found = lookup(batch);
                long t = System.currentTimeMillis();
                for (UUID id : batch) {
                    String cape = found.get(id);
                    Known before = known.put(id, new Known(cape == null ? NO_CAPE : cape, t));
                    if (cape != null && (before == null || !cape.equals(before.cape))) {
                        KestrelMod.LOG.info("[Kestrel] cape sharing: {} wears {}", id, cape);
                    }
                }
                lookupFailureLogged = false;
            } catch (Exception e) {
                if (!lookupFailureLogged) {
                    lookupFailureLogged = true;
                    KestrelMod.LOG.warn("[Kestrel] cape sharing: lookup failed (will retry): {}", e.toString());
                }
            } finally {
                inFlight.removeAll(batch);
                lookupRunning = false;
            }
        });
    }

    private Map<UUID, String> lookup(List<UUID> ids) throws IOException {
        URL url = new URL(baseUrl + "/v1/capes?uuids=" + CapeSyncProtocol.lookupQuery(ids));
        HttpURLConnection c = NightTls.open(url);
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "Night-Client/" + client.version());
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("service answered HTTP " + code + ": " + errorText(c));
            return CapeSyncProtocol.parseLookup(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private void forgetOld(long now) {
        for (Iterator<Map.Entry<UUID, Known>> it = known.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().fetchedMs > FORGET_AFTER_MS) it.remove();
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static String errorText(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            return in == null ? "" : read(in);
        } catch (IOException e) {
            return "";
        }
    }

    static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_REPLY) throw new IOException("reply too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static UUID parseUuid(String s) {
        try {
            String t = s.replace("-", "");
            return t.length() == 32 ? CapeSyncProtocol.parseUuid32(t.toLowerCase(java.util.Locale.ROOT)) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The service URL from the bundled config, or null when sharing is not set up. */
    static String loadServiceUrl() {
        try (InputStream in = CapeSync.class.getResourceAsStream(SERVICE_CONFIG)) {
            if (in == null) return null;
            JsonElement root = new JsonParser().parse(read(in));
            JsonElement u = root.isJsonObject() ? root.getAsJsonObject().get("url") : null;
            String url = u == null || u.isJsonNull() ? "" : u.getAsString().trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (url.isEmpty()) return null;
            if (!url.startsWith("https://")) {
                KestrelMod.LOG.warn("[Kestrel] cape sharing: service URL must be https: {}", url);
                return null;
            }
            return url;
        } catch (Exception e) {
            KestrelMod.LOG.warn("[Kestrel] cape sharing: bad {}: {}", SERVICE_CONFIG, e.toString());
            return null;
        }
    }
}
