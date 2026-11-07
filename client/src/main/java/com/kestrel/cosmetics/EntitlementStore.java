package com.kestrel.cosmetics;

import com.kestrel.KestrelMod;
import com.kestrel.util.NightTls;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which rewards the accounts this game plays as own. Each account's signed
 * document is read from {@code <instance>/kestrel/entitlements/<uuid>.json}
 * (the launcher puts a fresh copy there before the game starts and before an
 * in-game account switch completes) and then checked once with the service in
 * the background. Every copy is verified ({@link Entitlements#open}), so the
 * files can't be edited into rewards.
 *
 * <p>Game thread: {@link #has}. Network: one daemon thread.
 */
public final class EntitlementStore {
    private static final int TIMEOUT_MS = 8000;

    private final File dir;
    private final PublicKey key;
    private final String baseUrl;
    private final String userAgent;
    private final ExecutorService net;
    /** Verified rewards per 32-hex UUID (empty set: none). */
    private final Map<String, Set<String>> known = new ConcurrentHashMap<String, Set<String>>();
    private final Set<String> fetched = ConcurrentHashMap.newKeySet();

    public EntitlementStore(File dir, String baseUrl, String userAgent) {
        this.dir = dir;
        this.key = Entitlements.bundledKey();
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        if (key == null || baseUrl == null) {
            this.net = null;
            KestrelMod.LOG.info("[Kestrel] rewards: this build can't check redeemed rewards; built-in ones only");
        } else {
            this.net = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Night-Rewards");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** Whether the account (any UUID format; null for none) owns a reward. Game thread. */
    public boolean has(String uuid, String reward) {
        if (Entitlements.builtin(uuid).contains(reward)) return true;
        String u = CapeCatalog.normalizeUuid(uuid);
        if (u == null || key == null) return false;
        Set<String> r = known.get(u);
        if (r == null) {
            r = readCache(u);
            known.put(u, r);
        }
        if (net != null && fetched.add(u)) net.execute(() -> refresh(u));
        return r.contains(reward);
    }

    /** The account changed in game: read its rewards again (the launcher refreshed them). */
    public void onSessionChanged() {
        known.clear();
        fetched.clear();
    }

    private File file(String uuid32) {
        return new File(dir, uuid32 + ".json");
    }

    private Set<String> readCache(String uuid32) {
        File f = file(uuid32);
        if (!f.isFile()) return Collections.emptySet();
        try (InputStream in = new FileInputStream(f)) {
            Set<String> r = Entitlements.open(CapeSync.read(in), uuid32, key);
            KestrelMod.LOG.info("[Kestrel] rewards: {} saved reward(s) for this account", r.size());
            return r;
        } catch (Exception e) {
            KestrelMod.LOG.warn("[Kestrel] rewards: ignoring {} ({})", f.getName(), e.getMessage());
            return Collections.emptySet();
        }
    }

    private void refresh(String uuid32) {
        try {
            HttpURLConnection c = NightTls.open(new URL(baseUrl + "/v1/entitlements/" + uuid32));
            String body;
            try {
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", userAgent);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("service answered HTTP " + code);
                body = CapeSync.read(c.getInputStream());
            } finally {
                c.disconnect();
            }
            Set<String> r = Entitlements.open(body, uuid32, key);
            known.put(uuid32, r);
            write(uuid32, body);
            KestrelMod.LOG.info("[Kestrel] rewards: {} reward(s) for this account (checked with the service)", r.size());
        } catch (Exception e) {
            KestrelMod.LOG.info("[Kestrel] rewards: not checked with the service ({}); using the saved ones",
                    e.toString());
        }
    }

    private void write(String uuid32, String body) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, uuid32 + ".json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp.toPath(), file(uuid32).toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
