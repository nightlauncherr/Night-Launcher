package com.kestrel.sumo;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * {@code /stopsumo}: end the running match at once (not counted), remove the
 * opponent and return to the lobby. Only registered in the Sumo world.
 */
final class StopSumoCommand extends CommandBase {
    private final SumoServer server;

    StopSumoCommand(SumoServer server) {
        this.server = server;
    }

    @Override
    public String getCommandName() {
        return "stopsumo";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/stopsumo";
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
        SumoSession s = server.session();
        if (s == null || !s.stopMatch(true)) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.LIGHT_PURPLE + "[Sumo] " + EnumChatFormatting.GRAY
                    + "No Sumo match is running" + (s != null && s.phase() == SumoSession.Phase.EDIT ? " (edit mode is on)." : ".")));
        }
    }
}
