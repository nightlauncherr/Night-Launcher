package com.kestrel.config;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigManagerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void missingFileGivesDefaults() {
        ClientConfig c = ConfigManager.readOrDefault(new File(tmp.getRoot(), "nope.json"), new Gson());
        assertEquals(ClientConfig.CURRENT_VERSION, c.configVersion);
        assertTrue(c.killEffects.enabled);
    }

    @Test
    public void partialFileKeepsDefaultsAndClamps() throws Exception {
        File f = tmp.newFile("config.json");
        write(f, "{\"crosshair\":{\"length\":999,\"custom\":true},\"killEffects\":{\"volume\":-3}}");
        ClientConfig c = ConfigManager.readOrDefault(f, new Gson());
        assertTrue(c.crosshair.custom);
        assertEquals(20, c.crosshair.length);
        assertEquals(0f, c.killEffects.volume, 0f);
        assertEquals("burst", c.killEffects.effect); // default retained
        assertEquals(1, c.configVersion);
    }

    @Test
    public void malformedFileIsBackedUpNotLost() throws Exception {
        File f = tmp.newFile("config.json");
        write(f, "{ this is not json");
        ClientConfig c = ConfigManager.readOrDefault(f, new Gson());
        assertFalse(c.crosshair.custom);
        File bad = new File(tmp.getRoot(), "config.json.bad");
        assertTrue(bad.isFile());
        assertEquals("{ this is not json", new String(Files.readAllBytes(bad.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void wrongTypesFallBackToDefaults() throws Exception {
        File f = tmp.newFile("config.json");
        write(f, "{\"crosshair\":\"oops\"}");
        ClientConfig c = ConfigManager.readOrDefault(f, new Gson());
        assertFalse(c.crosshair.custom);
    }

    @Test
    public void saveRoundTripIsAtomic() throws Exception {
        File f = new File(tmp.getRoot(), "sub/config.json");
        ConfigManager m = new ConfigManager(f);
        m.load();
        m.get().sky.enabled = true;
        m.get().hudElement("fps", true, 0.2f, 0.3f);
        m.saveNow();
        assertTrue(f.isFile());
        assertFalse(new File(f.getParentFile(), "config.json.tmp").exists());
        ConfigManager m2 = new ConfigManager(f);
        m2.load();
        assertTrue(m2.get().sky.enabled);
        assertEquals(0.2f, m2.get().hud.get("fps").x, 0f);
        m.shutdown();
        m2.shutdown();
    }

    @Test
    public void fullbrightIsPersistedAndDefaultsOff() throws Exception {
        File f = tmp.newFile("config.json");
        write(f, "{\"crosshair\":{\"custom\":true}}"); // older file without "qol"
        ClientConfig c = ConfigManager.readOrDefault(f, new Gson());
        assertFalse(c.qol.fullbright);

        write(f, "{\"qol\":{\"fullbright\":true}}");
        assertTrue(ConfigManager.readOrDefault(f, new Gson()).qol.fullbright);

        write(f, "{\"qol\":null}");
        c = ConfigManager.readOrDefault(f, new Gson());
        assertFalse(c.qol.fullbright);

        write(f, "{\"qol\":{\"fullbright\":\"maybe\"}}"); // Gson reads a string as Boolean.parseBoolean: never true here
        c = ConfigManager.readOrDefault(f, new Gson());
        assertFalse(c.qol.fullbright);
    }

    @Test
    public void qolSettingsPersistAndClamp() throws Exception {
        File f = new File(tmp.getRoot(), "qol/config.json");
        ConfigManager m = new ConfigManager(f);
        m.load();
        ClientConfig.Qol q = m.get().qol;
        assertFalse(q.hitColor.enabled); // every new feature starts off
        assertFalse(q.noBlindness.enabled);
        q.hitColor.enabled = true;
        q.hitColor.color = 0xFF55FFFF;
        q.lowHealthAlert.threshold = 5f;
        q.weather.enabled = true;
        q.weather.rain = false;
        q.bridgeAssist.size = 4f;
        m.saveNow();
        ConfigManager m2 = new ConfigManager(f);
        m2.load();
        ClientConfig.Qol r = m2.get().qol;
        assertTrue(r.hitColor.enabled);
        assertEquals(0xFF55FFFF, r.hitColor.color);
        assertEquals(5f, r.lowHealthAlert.threshold, 0f);
        assertTrue(r.weather.enabled);
        assertFalse(r.weather.rain);
        assertEquals(4f, r.bridgeAssist.size, 0f);
        m.shutdown();
        m2.shutdown();

        File g = tmp.newFile("qol-bad.json");
        write(g, "{\"qol\":{\"hitColor\":{\"intensity\":5,\"duration\":-1},\"clearWater\":{\"clarity\":3},\"weather\":null}}");
        ClientConfig c = ConfigManager.readOrDefault(g, new Gson());
        assertEquals(0.9f, c.qol.hitColor.intensity, 0f);
        assertEquals(0.1f, c.qol.hitColor.duration, 0f);
        assertEquals(0.95f, c.qol.clearWater.clarity, 0f);
        assertTrue(c.qol.weather != null && c.qol.weather.rain);
    }
}
