package com.kestrel.openmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
 * Open Map start positions and settings, in {@code kestrel/openmap/layout.json}.
 * The blocks themselves live in the world save. Saved atomically, previous
 * file kept as {@code .prev}.
 */
public final class OpenMapLayout {
    public static final String DIR = "openmap";

    /** A position and facing. */
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
            return !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z) && y > 1 && y < 255
                    && Math.abs(x) < 30_000_000 && Math.abs(z) < 30_000_000;
        }

        @Override
        public String toString() {
            return String.format("%.1f, %.1f, %.1f", x, y, z);
        }
    }

    public Spot lobbySpawn;
    public Spot playerSpawn;
    public Spot aiSpawn;
    /** World border centre and size. */
    public double centerX;
    public double centerZ;
    public int size = 256;

    public static File dir(File dataDir) {
        return new File(dataDir, DIR);
    }

    public static File file(File dataDir) {
        return new File(dir(dataDir), "layout.json");
    }

    boolean complete() {
        return lobbySpawn != null && playerSpawn != null && aiSpawn != null
                && lobbySpawn.valid() && playerSpawn.valid() && aiSpawn.valid();
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    public static OpenMapLayout load(File f) {
        if (!f.isFile()) return null;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            OpenMapLayout l = gson().fromJson(r, OpenMapLayout.class);
            return l != null && l.complete() ? l : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void save(File f) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (f.isFile()) Files.copy(f.toPath(), new File(dir, f.getName() + ".prev").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson().toJson(this, w);
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
