package com.kestrel.openmap;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/** {@code /stopopenmap}: stop the match (not counted), back to the lobby, menu opens once. */
final class StopOpenMapCommand extends CommandBase {
    private final OpenMapServer server;

    StopOpenMapCommand(OpenMapServer server) {
        this.server = server;
    }

    @Override
    public String getCommandName() {
        return "stopopenmap";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/stopopenmap";
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
        if (s == null || !s.stopMatch(true)) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Open Map] " + EnumChatFormatting.GRAY
                    + "No Open Map match is running."));
        }
    }
}
