package com.kestrel.openmap;

import com.google.gson.Gson;
import com.kestrel.sumo.DifficultyMode;
import com.kestrel.sumo.Playstyle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Open Map opponent settings ({@code kestrel/openmap/opponent.json}), separate from Sumo's. */
public final class OpenMapPrefs {
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
        return new File(OpenMapLayout.dir(dataDir), "opponent.json");
    }

    public static OpenMapPrefs load(File f) {
        if (!f.isFile()) return new OpenMapPrefs();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            OpenMapPrefs p = new Gson().fromJson(r, OpenMapPrefs.class);
            return p == null ? new OpenMapPrefs() : p;
        } catch (IOException | RuntimeException e) {
            return new OpenMapPrefs();
        }
    }

    public void save(File f) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            new Gson().toJson(this, w);
        }
    }
}
