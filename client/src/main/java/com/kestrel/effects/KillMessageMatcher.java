package com.kestrel.effects;

import java.util.Locale;

/**
 * Kill-feed message matching (pure, unit-tested).
 *
 * <p>There is no universal kill event for 1.8.9 multiplayer: servers phrase
 * kill messages differently ("X was slain by Y", "X was knocked into the
 * void by Y", "Y killed X", ...). A line counts as <em>your</em> kill of the
 * victim only when it names both of you as whole words <em>and</em> its
 * wording makes you the killer:
 * <ul>
 *   <li>victim first, then "... by &lt;you&gt;" ("Steve was slain by Kellen");</li>
 *   <li>victim first, then "... to / with / from / against &lt;you&gt;" ending the
 *   sentence (Hypixel: "Steve died in close combat to Kellen.", "Steve fought
 *   to the edge with Kellen.", "Steve was hit off by a love bomb from Kellen.");</li>
 *   <li>victim first, killer as a possessive ("Steve was Kellen's final #12. FINAL KILL!");</li>
 *   <li>you first, then an active kill verb and the victim ("Kellen killed Steve").</li>
 * </ul>
 * "You were slain by the victim", assists ("... assisted by you"), other
 * players' kills and player chat never match. Lines that do not fit either
 * shape are ignored: a missed effect is better than a wrong one.
 */
public final class KillMessageMatcher {
    /** Verbs a kill feed uses for "killer &lt;verb&gt; victim". */
    private static final String[] ACTIVE_VERBS = {
            "killed", "slew", "slain", "eliminated", "destroyed", "finished", "defeated", "shot", "murdered",
            "executed", "obliterated", "knocked", "pushed", "blew up", "exploded", "rekt", "pwned", "took out",
            "struck down", "has killed", "final killed",
    };

    /** Words that, right before your name, make you the one the victim died to (besides "by"). */
    private static final String[] PASSIVE_CONNECTORS = {"to", "with", "from", "against"};

    private KillMessageMatcher() {
    }

    /**
     * The victim a kill-feed line names, when the line makes you the killer:
     * the first word of the line (kill feeds put the victim first), or the
     * victim of a "you killed X" line. Null when the line is not your kill.
     */
    public static String victimOf(String message, String self) {
        if (message == null || self == null || self.isEmpty()) return null;
        String m = stripFormatting(message).trim();
        if (looksLikePlayerChat(m)) return null;
        String first = firstName(m);
        // Kill feeds read "<victim> was/died/fought ...": a lowercase word follows the
        // name (not a header like "BED DESTRUCTION > ...").
        if (first != null && !first.equals(self) && lowercaseWordAfter(m, first) && isKillMessage(m, self, first)) return first;
        int si = wordIndex(m, self);
        if (si == 0) {
            // "<you> <verb> <victim>": the last name-like word after the verb.
            String rest = m.substring(self.length());
            String[] words = rest.split("[^A-Za-z0-9_]+");
            for (int i = words.length - 1; i >= 0; i--) {
                String w = words[i];
                if (isName(w) && !w.equals(self) && isKillMessage(m, self, w)) return w;
            }
        }
        return null;
    }

    private static boolean lowercaseWordAfter(String m, String name) {
        int i = wordIndex(m, name) + name.length();
        while (i < m.length() && m.charAt(i) == ' ') i++;
        return i < m.length() && Character.isLowerCase(m.charAt(i));
    }

    private static String firstName(String m) {
        int i = 0;
        while (i < m.length() && !isNameChar(m.charAt(i))) i++;
        int j = i;
        while (j < m.length() && isNameChar(m.charAt(j))) j++;
        String w = m.substring(i, j);
        return isName(w) ? w : null;
    }

    /** A Minecraft account name: 3-16 letters, digits or underscores. */
    static boolean isName(String w) {
        if (w == null || w.length() < 3 || w.length() > 16) return false;
        for (int i = 0; i < w.length(); i++) {
            if (!isNameChar(w.charAt(i))) return false;
        }
        return true;
    }

    /** Strip legacy section-sign colour codes. */
    public static String stripFormatting(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                i++;
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    public static boolean isKillMessage(String message, String self, String victim) {
        if (message == null || self == null || victim == null || self.isEmpty() || victim.isEmpty()
                || self.equalsIgnoreCase(victim)) {
            return false;
        }
        String m = stripFormatting(message);
        // Chat lines from players look like "<Name> text" or "Name: text"; a
        // player typing both names must not trigger an effect.
        if (looksLikePlayerChat(m)) {
            return false;
        }
        int vi = wordIndex(m, victim);
        int si = wordIndex(m, self);
        if (vi < 0 || si < 0) return false;
        if (vi < si) {
            String before = m.substring(vi + victim.length(), si).toLowerCase(Locale.ROOT).trim();
            String after = m.substring(si + self.length());
            if (before.contains("assist")) return false;
            // "<victim> ... by <you>": the word right before your name is "by"
            // (not "assisted by" / "and").
            if (endsWithWord(before, "by")) {
                String head = before.substring(0, before.length() - 2).trim();
                return !endsWithWord(head, "and");
            }
            // "<victim> was <you>'s final #12": you, as a possessive, right after "was".
            if (after.startsWith("'s ") || after.startsWith("\u2019s ")) {
                return before.equals("was") || before.equals("were") || before.equals("is");
            }
            // "<victim> died in close combat to <you>." and similar: a connector right
            // before your name, and the sentence ends with you (no other player after).
            for (String c : PASSIVE_CONNECTORS) {
                if (endsWithWord(before, c)) {
                    String head = before.substring(0, before.length() - c.length()).trim();
                    if (endsWithWord(head, "and") || " ".concat(head).contains(" and ")) return false;
                    return endsSentence(after);
                }
            }
            return false;
        }
        // "<you> ... <victim>": active voice only ("you killed victim"), never
        // "you were killed by victim".
        String between = " " + m.substring(si + self.length(), vi).toLowerCase(Locale.ROOT).trim() + " ";
        if (between.contains(" by ") || between.contains(" was ") || between.contains(" were ")
                || between.contains(" died") || between.contains(" assist")) {
            return false;
        }
        for (String verb : ACTIVE_VERBS) {
            if (between.contains(" " + verb + " ") || between.trim().equals(verb)) return true;
        }
        return false;
    }

    /**
     * What follows the killer's name is only the end of the sentence: punctuation,
     * "FINAL KILL!", or a bracketed note ("[5 coins]", "(x2)"); no further words.
     */
    static boolean endsSentence(String after) {
        String t = after.replaceAll("(?i)final kill!?", " ").replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ");
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetter(ch)) return false;
        }
        return true;
    }

    static boolean looksLikePlayerChat(String m) {
        String t = m.trim();
        if (t.startsWith("<") && t.indexOf('>') > 1) return true;
        int colon = t.indexOf(": ");
        // "[RANK] Name: message" style. Kill feeds rarely contain ": ".
        return colon > 0 && colon < 40;
    }

    /** Case-sensitive whole-word match ([A-Za-z0-9_] are word characters). */
    static boolean containsWord(String haystack, String word) {
        return wordIndex(haystack, word) >= 0;
    }

    /** Index of the first whole-word occurrence, or -1. */
    static int wordIndex(String haystack, String word) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(word, from);
            if (i < 0) return -1;
            int end = i + word.length();
            boolean leftOk = i == 0 || !isNameChar(haystack.charAt(i - 1));
            boolean rightOk = end == haystack.length() || !isNameChar(haystack.charAt(end));
            if (leftOk && rightOk) return i;
            from = i + 1;
        }
    }

    private static boolean endsWithWord(String s, String word) {
        if (!s.endsWith(word)) return false;
        int i = s.length() - word.length();
        return i == 0 || !isNameChar(s.charAt(i - 1));
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
