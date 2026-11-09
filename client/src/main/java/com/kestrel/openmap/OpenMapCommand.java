package com.kestrel.openmap;

import com.kestrel.sumo.DifficultyMode;
import com.kestrel.sumo.Playstyle;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /openmap ...}: everything the Open Map menu does. Only registered in
 * the Open Map world. Map editing (edit, save, setspawn, reset) is the owner's
 * only, checked here by UUID and again in the session.
 */
final class OpenMapCommand extends CommandBase {
    private static final String P = EnumChatFormatting.GREEN + "[Open Map] " + EnumChatFormatting.GRAY;
    private static final List<String> SUBS = Arrays.asList("start", "lobby", "stats", "difficulty", "style", "resetstats",
            "help", "edit", "save", "setspawn", "reset");
    private static final List<String> EDIT_SUBS = Arrays.asList("edit", "save", "setspawn", "reset");

    private final OpenMapServer server;

    OpenMapCommand(OpenMapServer server) {
        this.server = server;
    }

    @Override
    public String getCommandName() {
        return "openmap";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/openmap help";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        OpenMapSession s = server.session();
        if (s == null || s.stats() == null || s.phase() == OpenMapSession.Phase.STOPPED) {
            say(sender, EnumChatFormatting.GRAY + "No Open Map session is running.");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        String a1 = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean confirmed = args.length > 1 && Arrays.asList(args).subList(1, args.length).contains("confirm");
        if (EDIT_SUBS.contains(sub) && !OpenMapPermissions.canEdit(sender)) {
            say(sender, EnumChatFormatting.RED + OpenMapPermissions.DENIED);
            return;
        }
        switch (sub) {
            case "start":
                s.startMatch();
                break;
            case "lobby":
                if (s.phase() == OpenMapSession.Phase.EDIT) s.setEdit(false);
                else if (!s.stopMatch(false)) s.enterLobby(false);
                break;
            case "edit":
                s.setEdit(!"off".equals(a1));
                break;
            case "save":
                s.save();
                break;
            case "setspawn":
                if (!a1.equals("player") && !a1.equals("ai") && !a1.equals("bot") && !a1.equals("lobby")) {
                    say(sender, P + "Usage: /openmap setspawn player|ai|lobby (uses where you stand and look).");
                    return;
                }
                s.setSpawn(a1);
                break;
            case "reset":
                if (!confirmed) {
                    say(sender, EnumChatFormatting.YELLOW + "[Open Map] This replaces the whole map with freshly generated "
                            + "terrain the next time you open Open Map (the current map is kept as a backup). Type /openmap reset confirm");
                    return;
                }
                s.requestReset();
                break;
            case "difficulty": {
                DifficultyMode m = DifficultyMode.parse(a1);
                if (m == null) {
                    say(sender, P + "Usage: /openmap difficulty adaptive|easy|medium|hard|expert");
                    return;
                }
                s.setMode(m);
                break;
            }
            case "style": {
                Playstyle st = Playstyle.parse(a1);
                if (st == null) {
                    say(sender, P + "Usage: /openmap style balanced|aggressive|defensive|strafer");
                    return;
                }
                s.setStyle(st);
                break;
            }
            case "stats":
                stats(sender, s);
                break;
            case "resetstats":
                if (!confirmed) {
                    say(sender, EnumChatFormatting.YELLOW + "[Open Map] This resets Open Map statistics and what the opponent "
                            + "learned (Sumo is not touched; a backup is kept). Type /openmap resetstats confirm");
                    return;
                }
                try {
                    s.resetStats();
                    say(sender, P + "Open Map statistics reset.");
                } catch (IOException e) {
                    say(sender, EnumChatFormatting.RED + "[Open Map] Could not reset: " + e.getMessage());
                }
                break;
            default:
                help(sender, OpenMapPermissions.canEdit(sender));
                break;
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        boolean editor = OpenMapPermissions.canEdit(sender);
        if (args.length == 1) {
            List<String> subs = new ArrayList<String>(SUBS);
            if (!editor) subs.removeAll(EDIT_SUBS);
            return getListOfStringsMatchingLastWord(args, subs.toArray(new String[0]));
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("difficulty")) return getListOfStringsMatchingLastWord(args, "adaptive", "easy", "medium", "hard", "expert");
            if (sub.equals("style")) return getListOfStringsMatchingLastWord(args, "balanced", "aggressive", "defensive", "strafer");
            if (sub.equals("resetstats")) return getListOfStringsMatchingLastWord(args, "confirm");
            if (editor && sub.equals("edit")) return getListOfStringsMatchingLastWord(args, "off");
            if (editor && sub.equals("setspawn")) return getListOfStringsMatchingLastWord(args, "player", "ai", "lobby");
            if (editor && sub.equals("reset")) return getListOfStringsMatchingLastWord(args, "confirm");
        }
        return null;
    }

    private static void help(ICommandSender sender, boolean editor) {
        List<String> lines = new ArrayList<String>(Arrays.asList(
                EnumChatFormatting.GREEN + "Open Map PvP" + EnumChatFormatting.DARK_GRAY + " (M opens the menu)",
                "/openmap start - start (rounds then repeat by themselves)",
                "/stopopenmap - stop the match, back to the lobby",
                "/openmap lobby - back to the lobby",
                "/openmap difficulty adaptive|easy|medium|hard|expert",
                "/openmap style balanced|aggressive|defensive|strafer",
                "/openmap stats | /openmap resetstats confirm"));
        if (editor) {
            lines.add("/openmap edit | /openmap edit off - edit the map (creative, permanent)");
            lines.add("/openmap setspawn player|ai|lobby - set a start where you stand");
            lines.add("/openmap save - save the map now");
            lines.add("/openmap reset confirm - fresh terrain next time (current map backed up)");
        }
        for (String l : lines) say(sender, EnumChatFormatting.GRAY + l);
    }

    private static void stats(ICommandSender sender, OpenMapSession s) {
        PvpStats st = s.stats();
        String[] lines = {
                EnumChatFormatting.GREEN + "Open Map PvP",
                String.format("Matches %d: %d wins, %d losses, %d draws (%d%% wins)", st.matches, st.wins, st.losses, st.draws, st.winRatePercent()),
                String.format("Streak %d, best %d", st.winStreak(), st.bestWinStreak),
                String.format("Average match %.1fs, fastest win %.1fs", st.averageMatchMs() / 1000.0, st.fastestWinMs / 1000.0),
                String.format("Hits landed %d, taken %d; damage dealt %.0f, taken %.0f", st.hitsLanded, st.hitsTaken, st.damageDealt, st.damageTaken),
                String.format("Its rod hits %d, snowball hits %d, blocks placed %d (yours %d)", st.botRodHits, st.botSnowballHits,
                        st.botBlocksPlaced, st.playerBlocksPlaced),
                String.format("Difficulty %s, %d%% now (adaptive %d%%), playstyle %s; learned from %d matches", s.prefs().mode().label,
                        (int) Math.round(s.prefs().mode().skill(st.difficulty) * 100), (int) Math.round(st.difficulty * 100),
                        s.prefs().style().label, st.profile.samples),
        };
        for (String l : lines) say(sender, EnumChatFormatting.GRAY + l);
    }

    private static void say(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
