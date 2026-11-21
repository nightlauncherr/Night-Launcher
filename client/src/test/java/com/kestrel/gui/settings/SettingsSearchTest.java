package com.kestrel.gui.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SettingsSearchTest {
    @Test
    public void normalizes() {
        assertEquals("zoom speed", SettingsSearch.normalize("  Zoom   SPEED "));
        assertEquals("", SettingsSearch.normalize(null));
    }

    @Test
    public void emptyQueryMatchesNothing() {
        assertEquals(0, SettingsSearch.score("   ", "Zoom speed", "", "Zoom", "Movement"));
    }

    @Test
    public void everyWordMustMatch() {
        assertEquals(0, SettingsSearch.score("zoom cape", "Zoom speed", "", "Zoom", "Movement"));
        assertTrue(SettingsSearch.score("zoom speed", "Zoom speed", "", "Zoom", "Movement") > 0);
    }

    @Test
    public void nameBeatsSectionBeatsCategoryBeatsDescription() {
        int name = SettingsSearch.score("sky", "Custom sky", "", "Sky", "Cosmetics");
        int section = SettingsSearch.score("sky", "Brightness", "", "Sky settings", "Cosmetics");
        int category = SettingsSearch.score("cosmetics", "Brightness", "", "Sky settings", "Cosmetics");
        int description = SettingsSearch.score("overworld", "Custom sky", "Overworld only.", "Sky", "Cosmetics");
        assertTrue(name > section);
        assertTrue(section > category);
        assertTrue(category > description);
        assertTrue(description > 0);
    }

    @Test
    public void wordStartBeatsInsideWord() {
        int start = SettingsSearch.score("speed", "Zoom speed", "", "", "");
        int inside = SettingsSearch.score("peed", "Zoom speed", "", "", "");
        assertTrue(start > inside);
    }

    @Test
    public void exactNameRanksFirst() {
        int exact = SettingsSearch.score("hit color", "Hit Color", "", "Hit Color", "Combat");
        int partial = SettingsSearch.score("hit color", "Hit colour", "", "Hit Color", "Combat");
        assertTrue(exact > partial);
    }

    @Test
    public void caseInsensitive() {
        assertEquals(SettingsSearch.score("ZOOM", "Zoom", "", "", ""), SettingsSearch.score("zoom", "zoom", "", "", ""));
    }
}
