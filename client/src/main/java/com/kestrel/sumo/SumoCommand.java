package com.kestrel.sumo;

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
 * {@code /sumo ...}: everything the Sumo menu does, as commands. Only
 * registered in the Sumo world. Destructive actions need {@code confirm}.
 */
final class SumoCommand extends CommandBase {
    private static final String P = EnumChatFormatting.LIGHT_PURPLE + "[Sumo] " + EnumChatFormatting.GRAY;
    private static final List<String> SUBS = Arrays.asList("start", "lobby", "edit", "save", "reset", "setspawn", "help",
            "stats", "resetstats", "platform", "difficulty", "style");

    /** Subcommands that change the map. */
    private static final List<String> EDIT_SUBS = Arrays.asList("edit", "save", "reset", "setspawn", "platform");

    private final SumoServer server;

    SumoCommand(SumoServer server) {
        this.server = server;
    }

    @Override
    public String getCommandName() {
        return "sumo";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sumo help";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // practice world, works without cheats
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        SumoSession s = server.session();
        if (s == null || s.stats() == null || s.phase() == SumoSession.Phase.STOPPED) {
            say(sender, EnumChatFormatting.GRAY + "No Sumo session is running.");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        String a1 = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        String a2 = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        boolean confirmed = Arrays.asList(args).subList(Math.min(1, args.length), args.length).contains("confirm");
        // Arena editing belongs to the map's owner (checked by UUID, not name); the session
        // checks again for every edit action.
        if (EDIT_SUBS.contains(sub) && !SumoPermissions.canEdit(sender)) {
            say(sender, EnumChatFormatting.RED + SumoPermissions.DENIED);
            return;
        }
        switch (sub) {
            case "start":
            case "rematch":
                s.startMatch();
                break;
            case "lobby":
                if (s.phase() == SumoSession.Phase.EDIT) s.setEdit(false);
                else if (!s.stopMatch(false)) s.enterLobby(false);
                break;
            case "edit":
                s.setEdit(!"off".equals(a1));
                break;
            case "save":
                s.saveArena();
                break;
            case "reset":
                if ("default".equals(a1)) {
                    if (!confirmed) {
                        say(sender, EnumChatFormatting.YELLOW + "[Sumo] This puts back the official Sumo map (arena and lobby). "
                                + "The current blocks are backed up first. Type /sumo reset default confirm");
                        return;
                    }
                    s.restoreOfficial();
                } else {
                    if (!confirmed) {
                        say(sender, EnumChatFormatting.YELLOW + "[Sumo] This puts back your last saved arena (/sumo save), "
                                + "undoing unsaved building. Type /sumo reset confirm");
                        return;
                    }
                    s.restoreArena();
                }
                break;
            case "setspawn":
                if (!a1.equals("player") && !a1.equals("ai") && !a1.equals("bot") && !a1.equals("lobby")) {
                    say(sender, P + "Usage: /sumo setspawn player|ai|lobby (uses where you stand and look).");
                    return;
                }
                s.setSpawn(a1);
                break;
            case "platform": {
                int size;
                try {
                    size = Integer.parseInt(a1);
                } catch (NumberFormatException e) {
                    say(sender, P + "Usage: /sumo platform <size " + ArenaLayout.MIN_SIZE + "-" + ArenaLayout.MAX_SIZE
                            + "> <block> confirm. Blocks: " + materialList());
                    return;
                }
                String mat = a2.isEmpty() || a2.equals("confirm") ? s.layout().platformMaterial : a2;
                if (!ArenaLayout.isMaterial(mat)) {
                    say(sender, EnumChatFormatting.RED + "[Sumo] Unknown block. Choose one of: " + materialList());
                    return;
                }
                int clamped = ArenaLayout.clampSize(size);
                if (!confirmed) {
                    say(sender, EnumChatFormatting.YELLOW + "[Sumo] This rebuilds the arena as a " + clamped + " x " + clamped + " "
                            + ArenaLayout.materialLabel(mat) + " platform and resets the start positions. The current blocks are "
                            + "backed up first. Type /sumo platform " + clamped + " " + mat + " confirm");
                    return;
                }
                s.rebuildDefault(clamped, mat, "before-platform");
                break;
            }
            case "difficulty": {
                DifficultyMode m = DifficultyMode.parse(a1);
                if (m == null) {
                    say(sender, P + "Usage: /sumo difficulty adaptive|easy|medium|hard|expert");
                    return;
                }
                s.setMode(m);
                break;
            }
            case "style": {
                Playstyle st = Playstyle.parse(a1);
                if (st == null) {
                    say(sender, P + "Usage: /sumo style balanced|aggressive|defensive|strafer");
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
                    say(sender, EnumChatFormatting.YELLOW + "[Sumo] This resets all Sumo statistics, the adaptive difficulty and "
                            + "what the opponent learned (the arena is not touched). Type /sumo resetstats confirm");
                    return;
                }
                try {
                    s.resetStats();
                    say(sender, P + "Statistics reset (a backup of the old file was kept next to it).");
                } catch (IOException e) {
                    say(sender, EnumChatFormatting.RED + "[Sumo] Could not reset: " + e.getMessage());
                }
                break;
            default:
                help(sender, SumoPermissions.canEdit(sender));
                break;
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<String>(SUBS);
            if (!SumoPermissions.canEdit(sender)) subs.removeAll(EDIT_SUBS);
            return getListOfStringsMatchingLastWord(args, subs.toArray(new String[0]));
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "edit":
                    return getListOfStringsMatchingLastWord(args, "off");
                case "reset":
                    return getListOfStringsMatchingLastWord(args, "confirm", "default");
                case "resetstats":
                    return getListOfStringsMatchingLastWord(args, "confirm");
                case "setspawn":
                    return getListOfStringsMatchingLastWord(args, "player", "ai", "lobby");
                case "difficulty":
                    return getListOfStringsMatchingLastWord(args, "adaptive", "easy", "medium", "hard", "expert");
                case "style":
                    return getListOfStringsMatchingLastWord(args, "balanced", "aggressive", "defensive", "strafer");
                default:
                    return null;
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("reset")) return getListOfStringsMatchingLastWord(args, "confirm");
            if (args[0].equalsIgnoreCase("platform")) {
                List<String> ids = new ArrayList<String>();
                for (String[] m : ArenaLayout.MATERIALS) ids.add(m[0]);
                return getListOfStringsMatchingLastWord(args, ids.toArray(new String[0]));
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("platform")) return getListOfStringsMatchingLastWord(args, "confirm");
        return null;
    }

    private static String materialList() {
        StringBuilder b = new StringBuilder();
        for (String[] m : ArenaLayout.MATERIALS) {
            if (b.length() > 0) b.append(", ");
            b.append(m[0]);
        }
        return b.toString();
    }

    private static void help(ICommandSender sender, boolean editor) {
        String[] common = {
                EnumChatFormatting.LIGHT_PURPLE + "Sumo practice commands" + EnumChatFormatting.DARK_GRAY + " (M opens the Sumo menu)",
                "/sumo start - start a match (rounds then repeat by themselves)",
                "/stopsumo - stop the running match, back to the lobby",
                "/sumo lobby - back to the lobby",
                "/sumo difficulty adaptive|easy|medium|hard|expert",
                "/sumo style balanced|aggressive|defensive|strafer",
                "/sumo stats | /sumo resetstats confirm",
        };
        if (!editor) {
            for (String l : common) say(sender, EnumChatFormatting.GRAY + l);
            return;
        }
        String[] lines = {
                EnumChatFormatting.LIGHT_PURPLE + "Sumo practice commands" + EnumChatFormatting.DARK_GRAY + " (M opens the Sumo menu)",
                "/sumo start - start a match (also REMATCH)",
                "/stopsumo - stop the running match, back to the lobby",
                "/sumo lobby - back to the lobby",
                "/sumo edit | /sumo edit off - build the arena in creative",
                "/sumo setspawn player|ai|lobby - set a start where you stand",
                "/sumo save - save the arena blocks and start positions",
                "/sumo reset confirm - restore your last saved arena",
                "/sumo reset default confirm - official map back (current one backed up)",
                "/sumo platform <size> <block> confirm - rebuild a square platform",
                "/sumo difficulty adaptive|easy|medium|hard|expert",
                "/sumo style balanced|aggressive|defensive|strafer",
                "/sumo stats | /sumo resetstats confirm",
        };
        for (String l : lines) say(sender, EnumChatFormatting.GRAY + l);
    }

    private static void stats(ICommandSender sender, SumoSession s) {
        SumoStats st = s.stats();
        String[] lines = {
                EnumChatFormatting.LIGHT_PURPLE + "Sumo practice",
                String.format("Matches %d: %d wins, %d losses, %d draws (%d%% wins)", st.matches, st.wins, st.losses, st.draws, st.winRatePercent()),
                String.format("Streak %d, best %d", st.winStreak(), st.bestWinStreak),
                String.format("Average match %.1fs, fastest win %.1fs, longest %.1fs",
                        st.averageMatchMs() / 1000.0, st.fastestWinMs / 1000.0, st.longestMatchMs / 1000.0),
                String.format("Hits landed %d, taken %d", st.hitsLanded, st.hitsTaken),
                String.format("Difficulty %s, %d%% now (adaptive level %d%%), playstyle %s", s.prefs().mode().label,
                        (int) Math.round(s.prefs().mode().skill(st.difficulty) * 100), (int) Math.round(st.difficulty * 100),
                        s.prefs().style().label),
                EnumChatFormatting.DARK_GRAY + "Saved in " + s.statsFile().getPath(),
        };
        for (String l : lines) say(sender, EnumChatFormatting.GRAY + l);
    }

    private static void say(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
