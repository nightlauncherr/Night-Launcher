package com.kestrel.sumo;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/**
 * {@code /sumo stats} shows the overall practice statistics;
 * {@code /sumo reset confirm} resets statistics, difficulty and learning
 * (the old file is kept as a backup). Only registered in the Sumo world.
 */
final class SumoCommand extends CommandBase {
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
        return "/sumo stats | /sumo reset confirm";
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
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        SumoSession s = server.session();
        if (s == null || s.stats() == null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "No Sumo session is running."));
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "stats";
        if (sub.equals("reset")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW
                        + "This resets all Sumo statistics, the difficulty and what the bot learned. Type /sumo reset confirm"));
                return;
            }
            try {
                s.resetStats();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.LIGHT_PURPLE
                        + "Sumo statistics reset (a backup of the old file was kept next to it)."));
            } catch (IOException e) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Could not reset: " + e.getMessage()));
            }
            return;
        }
        SumoStats st = s.stats();
        String[] lines = {
                EnumChatFormatting.LIGHT_PURPLE + "Sumo practice",
                String.format("Matches %d: %d wins, %d losses, %d draws (%d%% wins)", st.matches, st.wins, st.losses, st.draws, st.winRatePercent()),
                String.format("Streak %d, best %d", st.winStreak(), st.bestWinStreak),
                String.format("Average match %.1fs, fastest win %.1fs, longest %.1fs",
                        st.averageMatchMs() / 1000.0, st.fastestWinMs / 1000.0, st.longestMatchMs / 1000.0),
                String.format("Hits landed %d, taken %d", st.hitsLanded, st.hitsTaken),
                String.format("Difficulty %d%%", (int) Math.round(st.difficulty * 100)),
                EnumChatFormatting.DARK_GRAY + "Saved in " + server.statsFile().getPath(),
        };
        for (String l : lines) sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + l));
    }
}
