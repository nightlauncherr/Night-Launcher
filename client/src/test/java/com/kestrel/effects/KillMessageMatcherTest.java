package com.kestrel.effects;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KillMessageMatcherTest {
    @Test
    public void matchesYourKills() {
        assertTrue(KillMessageMatcher.isKillMessage("Steve was killed by Kellen.", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Steve was slain by Kellen", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Steve was slain by Kellen using [Diamond Sword]", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("§cSteve §7was slain by §aKellen§7. §b§lFINAL KILL!", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Steve was knocked into the void by Kellen.", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Steve was shot by Kellen", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Kellen killed Steve!", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Kellen has killed Steve", "Kellen", "Steve"));
        assertTrue(KillMessageMatcher.isKillMessage("Kellen eliminated Steve", "Kellen", "Steve"));
    }

    @Test
    public void yourOwnDeathNeverMatches() {
        assertFalse(KillMessageMatcher.isKillMessage("Kellen was slain by Steve", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Kellen was knocked into the void by Steve.", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve killed Kellen!", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Kellen died", "Kellen", "Steve"));
    }

    @Test
    public void otherKillsAssistsAndEnvironmentDoNotMatch() {
        assertFalse(KillMessageMatcher.isKillMessage("Steve was killed by Alex.", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve was killed by Alex, assisted by Kellen", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve was slain by Alex and Kellen", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve fell from a high place", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve tried to swim in lava to escape Kellen", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Kellen and Steve joined the game", "Kellen", "Steve"));
    }

    @Test
    public void rejectsChatAndPartialNames() {
        assertFalse(KillMessageMatcher.isKillMessage("<Steve> Kellen killed me lol", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("[MVP+] Steve: gg Kellen", "Kellen", "Steve"));
        // Whole-word matching: names that merely contain the target do not count.
        assertFalse(KillMessageMatcher.isKillMessage("Steve2 was killed by Kellen.", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve was killed by Kellen_X.", "Kellen", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("x", "Kellen", "Kellen"));
        assertFalse(KillMessageMatcher.isKillMessage(null, "Kellen", "Steve"));
    }

    // Real Hypixel lines (from a SkyWars/Bed Wars session log), local player "Bringing".

    @Test
    public void hypixelKillLinesMatch() {
        assertTrue(KillMessageMatcher.isKillMessage("ghudbob died in close combat to Bringing.", "Bringing", "ghudbob"));
        assertTrue(KillMessageMatcher.isKillMessage("captain25016382 fought to the edge with Bringing.", "Bringing", "captain25016382"));
        assertTrue(KillMessageMatcher.isKillMessage("TheHouseDino died in close combat to Bringing.", "Bringing", "TheHouseDino"));
        assertTrue(KillMessageMatcher.isKillMessage("\u00a7cTheHouseDino \u00a77fought to the edge with \u00a7aBringing\u00a77.", "Bringing", "TheHouseDino"));
        // Shapes other players' kills used in the same log, with you as the killer:
        assertTrue(KillMessageMatcher.isKillMessage("ghudbob was hit off by a love bomb from Bringing.", "Bringing", "ghudbob"));
        assertTrue(KillMessageMatcher.isKillMessage("FluffyGirlyReal was Bringing's final #21,313. FINAL KILL!", "Bringing", "FluffyGirlyReal"));
        assertTrue(KillMessageMatcher.isKillMessage("Drechnoz was given the cold shoulder by Bringing.", "Bringing", "Drechnoz"));
        assertTrue(KillMessageMatcher.isKillMessage("DarkDesk was knocked off a cliff by Bringing. FINAL KILL!", "Bringing", "DarkDesk"));
        assertTrue(KillMessageMatcher.isKillMessage("Didi194 got rekt by Bringing.", "Bringing", "Didi194"));
        assertTrue(KillMessageMatcher.isKillMessage("huangbu be killed with magic by Bringing.", "Bringing", "huangbu"));
        assertTrue(KillMessageMatcher.isKillMessage("ghudbob slipped in BBQ sauce off the edge spilled by Bringing.", "Bringing", "ghudbob"));
        assertTrue(KillMessageMatcher.isKillMessage("Rimont was filled full of lead by Bringing. FINAL KILL!", "Bringing", "Rimont"));
    }

    @Test
    public void hypixelLinesThatAreNotYourKillDoNotMatch() {
        // You died.
        assertFalse(KillMessageMatcher.isKillMessage("Bringing was thrown down a pit by ghudbob.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("Bringing was given the cold shoulder by sinustheshrew. FINAL KILL!", "Bringing", "sinustheshrew"));
        assertFalse(KillMessageMatcher.isKillMessage("Bringing died in close combat to ghudbob.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("Bringing fought to the edge with ghudbob.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("Bringing was ghudbob's final #21,311. FINAL KILL!", "Bringing", "ghudbob"));
        // Someone else's kill, or no killer at all.
        assertFalse(KillMessageMatcher.isKillMessage("captain25016382 was ghudbob's final #21,311. FINAL KILL!", "Bringing", "captain25016382"));
        assertFalse(KillMessageMatcher.isKillMessage("ghudbob fell into the void.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("BED DESTRUCTION > Your Bed was destroyed by TheHouseDino!", "Bringing", "TheHouseDino"));
        // Connector words that don't make you the killer.
        assertFalse(KillMessageMatcher.isKillMessage("ghudbob died in close combat to Bringing and Alex.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("ghudbob and Alex teamed up with Bringing.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("ghudbob died in close combat to Bringing's teammate Alex.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("ghudbob killed Bringing.", "Bringing", "ghudbob"));
        assertFalse(KillMessageMatcher.isKillMessage("Steve tried to swim in lava to escape Bringing", "Bringing", "Steve"));
        assertFalse(KillMessageMatcher.isKillMessage("[VIP+] ghudbob: gg i lost to Bringing", "Bringing", "ghudbob"));
    }

    @Test
    public void victimIsReadFromTheLine() {
        org.junit.Assert.assertEquals("ghudbob", KillMessageMatcher.victimOf("ghudbob died in close combat to Bringing.", "Bringing"));
        org.junit.Assert.assertEquals("FluffyGirlyReal",
                KillMessageMatcher.victimOf("\u00a7cFluffyGirlyReal \u00a77was Bringing's final #21,313. \u00a7b\u00a7lFINAL KILL!", "Bringing"));
        org.junit.Assert.assertEquals("Steve", KillMessageMatcher.victimOf("Bringing killed Steve!", "Bringing"));
        org.junit.Assert.assertNull(KillMessageMatcher.victimOf("Bringing was thrown down a pit by ghudbob.", "Bringing"));
        org.junit.Assert.assertNull(KillMessageMatcher.victimOf("ghudbob fell into the void.", "Bringing"));
        org.junit.Assert.assertNull(KillMessageMatcher.victimOf("BED DESTRUCTION > Blue Bed was destroyed by Bringing!", "Bringing"));
        org.junit.Assert.assertNull(KillMessageMatcher.victimOf("<ghudbob> Bringing killed Steve", "Bringing"));
    }
}

