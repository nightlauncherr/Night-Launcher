package com.kestrel.sumo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kestrel.KestrelMod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The official Night Sumo map, shipped inside the client: the owner's
 * customized Sumo world (blocks, lobby, decorations, spawn) as a world folder,
 * plus its start positions. It is installed into {@code saves/} the first time
 * a player opens Sumo, and from then on their copy is simply loaded: never
 * regenerated, never reset between matches.
 *
 * <p>A Sumo world from before the official map (no marker file, or an older
 * map version) is moved aside, never deleted, and the official one installed in
 * its place. No other world is read or written.
 */
final class OfficialMap {
    static final String WORLD_ZIP = "/assets/kestrel/sumo/official-world.zip";
    static final String LAYOUT = "/assets/kestrel/sumo/official-arena.json";
    /** Written into the installed world; says which map version it is. */
    static final String MARKER = "night-official-map.json";

    private OfficialMap() {
    }

    /** The map version shipped in this client (from the marker inside the zip). */
    static int shippedVersion() {
        try (InputStream in = OfficialMap.class.getResourceAsStream(WORLD_ZIP)) {
            if (in == null) return 0;
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (MARKER.equals(e.getName())) return version(new InputStreamReader(zip, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            KestrelMod.LOG.error("[Kestrel] the official Sumo map in this build can't be read", e);
        }
        return 0;
    }

    /** The version of an installed Sumo world, 0 when it isn't (a copy of) the official map. */
    static int installedVersion(File worldDir) {
        File marker = new File(worldDir, MARKER);
        if (!marker.isFile()) return 0;
        try (Reader r = new InputStreamReader(new java.io.FileInputStream(marker), StandardCharsets.UTF_8)) {
            return version(r);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static int version(Reader r) {
        JsonObject o = new Gson().fromJson(r, JsonObject.class);
        return o != null && o.has("version") ? o.get("version").getAsInt() : 0;
    }

    /**
     * Make sure {@code saves/<Sumo folder>} holds the official map. Returns false
     * when it couldn't (the caller then doesn't open anything).
     */
    static boolean ensureInstalled(File savesDir, File dataDir) {
        int shipped = shippedVersion();
        if (shipped <= 0) {
            KestrelMod.LOG.error("[Kestrel] this build has no official Sumo map");
            return false;
        }
        File dir = new File(savesDir, SumoArena.WORLD_FOLDER);
        if (dir.isDirectory() && new File(dir, "level.dat").isFile() && installedVersion(dir) >= shipped) {
            return true; // their copy of the official map, as they left it
        }
        if (dir.exists()) {
            File aside = new File(savesDir, SumoArena.WORLD_FOLDER + "-before-official-" + System.currentTimeMillis());
            if (!dir.renameTo(aside)) {
                KestrelMod.LOG.error("[Kestrel] can't move the old Sumo world aside ({}), not installing the official map", dir);
                return false;
            }
            KestrelMod.LOG.info("[Kestrel] kept the previous Sumo world as {}", aside.getName());
        }
        File tmp = new File(savesDir, SumoArena.WORLD_FOLDER + ".installing");
        try {
            deleteTree(tmp); // only ever our own half-finished install
            unzip(tmp);
            if (!new File(tmp, "level.dat").isFile()) throw new IOException("the official map has no level.dat");
            if (!tmp.renameTo(dir)) throw new IOException("cannot rename " + tmp + " to " + dir);
        } catch (IOException e) {
            KestrelMod.LOG.error("[Kestrel] installing the official Sumo map failed", e);
            deleteTree(tmp);
            return false;
        }
        // Its start positions replace the old ones (arena.json keeps the old file as .prev).
        try {
            layout().save(ArenaLayout.file(dataDir));
        } catch (IOException e) {
            KestrelMod.LOG.error("[Kestrel] cannot write the official Sumo start positions", e);
        }
        KestrelMod.LOG.info("[Kestrel] installed the official Sumo map (version {})", shipped);
        return true;
    }

    /** The official start positions and arena settings. */
    static ArenaLayout layout() {
        try (InputStream in = OfficialMap.class.getResourceAsStream(LAYOUT)) {
            if (in != null) {
                ArenaLayout l = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ArenaLayout.class);
                if (l != null) {
                    l.normalize();
                    return l;
                }
            }
        } catch (IOException | RuntimeException e) {
            KestrelMod.LOG.error("[Kestrel] the official Sumo start positions can't be read", e);
        }
        return ArenaLayout.defaults();
    }

    private static void unzip(File target) throws IOException {
        try (InputStream raw = OfficialMap.class.getResourceAsStream(WORLD_ZIP)) {
            if (raw == null) throw new IOException("missing " + WORLD_ZIP);
            ZipInputStream zip = new ZipInputStream(raw);
            String root = target.getCanonicalPath() + File.separator;
            byte[] buf = new byte[65536];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                File out = new File(target, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad entry " + e.getName());
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create " + out);
                    continue;
                }
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                try (OutputStream os = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0; ) os.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) KestrelMod.LOG.warn("[Kestrel] could not remove {}", f);
    }
}
