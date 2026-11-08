package com.kestrel.sumo;

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

/** Opponent choices: difficulty mode and playstyle. {@code <instance>/kestrel/sumo/opponent.json}. */
public final class SumoPrefs {
    public static final String FILE = "opponent.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String difficulty = DifficultyMode.ADAPTIVE.name();
    public String playstyle = Playstyle.BALANCED.name();

    public DifficultyMode mode() {
        DifficultyMode m = DifficultyMode.parse(difficulty);
        return m == null ? DifficultyMode.ADAPTIVE : m;
    }

    public Playstyle style() {
        Playstyle p = Playstyle.parse(playstyle);
        return p == null ? Playstyle.BALANCED : p;
    }

    public static File file(File dataDir) {
        return new File(ArenaLayout.dir(dataDir), FILE);
    }

    public static SumoPrefs load(File file) {
        if (!file.isFile()) return new SumoPrefs();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            SumoPrefs p = GSON.fromJson(r, SumoPrefs.class);
            if (p == null) return new SumoPrefs();
            p.difficulty = p.mode().name();
            p.playstyle = p.style().name();
            return p;
        } catch (IOException | RuntimeException e) {
            return new SumoPrefs();
        }
    }

    public void save(File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, file.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
