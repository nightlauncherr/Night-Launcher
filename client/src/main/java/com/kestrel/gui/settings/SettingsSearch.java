package com.kestrel.gui.settings;

import java.util.Locale;

/**
 * Matching for the settings search box. Pure (no Minecraft types) so it is
 * unit-tested. Every word of the query must appear in the setting's name,
 * its description, its section or its category; the score ranks name
 * matches first.
 */
public final class SettingsSearch {
    private SettingsSearch() {
    }

    /** Lower case, trimmed, runs of spaces collapsed. */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * @return 0 when the setting does not match, otherwise a score (higher is better)
     */
    public static int score(String query, String name, String description, String section, String category) {
        String q = normalize(query);
        if (q.isEmpty()) return 0;
        String n = normalize(name);
        String d = normalize(description);
        String s = normalize(section);
        String c = normalize(category);
        int total = 0;
        for (String word : q.split(" ")) {
            int best;
            if (n.startsWith(word) || n.contains(" " + word)) best = 100;
            else if (n.contains(word)) best = 70;
            else if (s.contains(word)) best = 40;
            else if (c.contains(word)) best = 30;
            else if (d.contains(word)) best = 15;
            else return 0;
            total += best;
        }
        if (n.equals(q)) total += 200;
        return total;
    }
}
