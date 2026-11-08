package com.kestrel.sumo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The Sumo map settings: start positions, lobby, the default platform's size
 * and block, and the area that {@code /sumo save} snapshots. Stored in
 * {@code <instance>/kestrel/sumo/arena.json}, separate from the statistics, so
 * arena changes can never touch wins, streaks, difficulty or learning.
 *
 * <p>The blocks themselves live in the world; the snapshot next to this file
 * ({@link #SNAPSHOT_FILE}) is what {@code /sumo reset} restores.
 */
public final class ArenaLayout {
    public static final String DIR = "sumo";
    public static final String FILE = "arena.json";
    public static final String SNAPSHOT_FILE = "arena-blocks.dat";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int MIN_SIZE = 7;
    public static final int MAX_SIZE = 41;

    /** A position with a look direction. */
    public static final class Spot {
        public double x;
        public double y;
        public double z;
        public float yaw;

        public Spot() {
        }

        public Spot(double x, double y, double z, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        boolean valid() {
            return finite(x) && finite(y) && finite(z) && finite(yaw) && y > 0 && y < 255
                    && Math.abs(x) < 30_000_000 && Math.abs(z) < 30_000_000;
        }

        @Override
        public String toString() {
            return String.format("%.1f, %.1f, %.1f", x, y, z);
        }
    }

    public int version = 1;
    public Spot playerSpawn;
    public Spot aiSpawn;
    public Spot lobbySpawn;
    /** Default platform: side length (odd) and block id (see {@link #MATERIALS}). */
    public int platformSize = SumoArena.MAX - SumoArena.MIN + 1;
    public String platformMaterial = MATERIALS[0][0];
    /** Snapshot area (inclusive block bounds) for save/reset. */
    public int regionMinX = -32;
    public int regionMaxX = 32;
    public int regionMinY = 64;
    public int regionMaxY = 136;
    public int regionMinZ = -32;
    public int regionMaxZ = 32;
    public long savedEpochMs;

    /** Selectable platform blocks: {id, label}. Ids are resolved by {@code SumoWorldSetup}. */
    public static final String[][] MATERIALS = {
            {"light_gray_clay", "Light gray clay"},
            {"stone", "Stone"},
            {"white_wool", "White wool"},
            {"quartz", "Quartz"},
            {"sandstone", "Sandstone"},
            {"snow", "Snow"},
            {"black_clay", "Black clay"},
    };

    /** The original layout: the 15 x 15 platform with the lobby behind it. */
    public static ArenaLayout defaults() {
        ArenaLayout l = new ArenaLayout();
        l.applyDefaultSpawns();
        return l;
    }

    /** Start positions for a default platform of {@link #platformSize}, and the default lobby spot. */
    void applyDefaultSpawns() {
        double offset = spawnOffset(platformSize);
        playerSpawn = new Spot(SumoArena.CENTER_X - offset, SumoArena.SURFACE_Y, SumoArena.CENTER_Z, -90f);
        aiSpawn = new Spot(SumoArena.CENTER_X + offset, SumoArena.SURFACE_Y, SumoArena.CENTER_Z, 90f);
        lobbySpawn = new Spot(lobbyCenterX(), SumoArena.SURFACE_Y, lobbyCenterZ() - 1.5, 0f);
    }

    /** About a quarter of the platform from the centre (3.5 for the default 15). */
    static double spawnOffset(int size) {
        return Math.max(1.5, Math.round(size * 0.233 * 2) / 2.0);
    }

    /** The default lobby: a 7 x 7 platform clear of the largest arena. */
    public static final int LOBBY_HALF = 3;

    public double lobbyCenterX() {
        return SumoArena.CENTER_X;
    }

    public double lobbyCenterZ() {
        return -(MAX_SIZE / 2) - 6 + 0.5;
    }

    /** Feet below this have fallen off: 3 blocks under the lower start position. */
    public double loseY() {
        return Math.min(playerSpawn.y, aiSpawn.y) - 3.0;
    }

    public static String materialLabel(String id) {
        for (String[] m : MATERIALS) if (m[0].equals(id)) return m[1];
        return id;
    }

    public static String nextMaterial(String id) {
        for (int i = 0; i < MATERIALS.length; i++) {
            if (MATERIALS[i][0].equals(id)) return MATERIALS[(i + 1) % MATERIALS.length][0];
        }
        return MATERIALS[0][0];
    }

    public static boolean isMaterial(String id) {
        for (String[] m : MATERIALS) if (m[0].equals(id)) return true;
        return false;
    }

    public static int clampSize(int size) {
        int s = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        return s % 2 == 0 ? s + 1 : s;
    }

    public static File dir(File dataDir) {
        return new File(dataDir, DIR);
    }

    public static File file(File dataDir) {
        return new File(dir(dataDir), FILE);
    }

    public static File snapshot(File dataDir) {
        return new File(dir(dataDir), SNAPSHOT_FILE);
    }

    /** The saved layout, or null when there is none (or it can't be read; the file is kept). */
    public static ArenaLayout load(File file) {
        if (!file.isFile()) return null;
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ArenaLayout l = GSON.fromJson(r, ArenaLayout.class);
            if (l == null) throw new JsonParseException("empty");
            l.normalize();
            return l;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Write atomically, keeping the previous file as {@code arena.json.prev}. */
    public void save(File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        backup(file);
        File tmp = new File(dir, file.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Copy {@code file} to {@code file.prev} (one level of undo) before it is replaced. */
    public static void backup(File file) throws IOException {
        if (file.isFile()) {
            Files.copy(file.toPath(), new File(file.getPath() + ".prev").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public ArenaLayout copy() {
        return GSON.fromJson(GSON.toJson(this), ArenaLayout.class);
    }

    void normalize() {
        platformSize = clampSize(platformSize);
        if (!isMaterial(platformMaterial)) platformMaterial = MATERIALS[0][0];
        ArenaLayout d = null;
        if (playerSpawn == null || !playerSpawn.valid() || aiSpawn == null || !aiSpawn.valid()
                || lobbySpawn == null || !lobbySpawn.valid()) {
            d = defaults();
        }
        if (d != null) {
            if (playerSpawn == null || !playerSpawn.valid()) playerSpawn = d.playerSpawn;
            if (aiSpawn == null || !aiSpawn.valid()) aiSpawn = d.aiSpawn;
            if (lobbySpawn == null || !lobbySpawn.valid()) lobbySpawn = d.lobbySpawn;
        }
        if (regionMinX > regionMaxX || regionMinY > regionMaxY || regionMinZ > regionMaxZ
                || (long) (regionMaxX - regionMinX + 1) * (regionMaxY - regionMinY + 1) * (regionMaxZ - regionMinZ + 1) > 2_000_000L
                || regionMinY < 0 || regionMaxY > 255) {
            ArenaLayout def = new ArenaLayout();
            regionMinX = def.regionMinX;
            regionMaxX = def.regionMaxX;
            regionMinY = def.regionMinY;
            regionMaxY = def.regionMaxY;
            regionMinZ = def.regionMinZ;
            regionMaxZ = def.regionMaxZ;
        }
    }

    public boolean regionContains(double x, double y, double z) {
        return x >= regionMinX && x < regionMaxX + 1 && y >= regionMinY && y < regionMaxY + 1
                && z >= regionMinZ && z < regionMaxZ + 1;
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
