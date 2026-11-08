package com.kestrel.sumo;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;

import java.util.UUID;

/**
 * Who may edit the Sumo map. Only the map's owner, identified by Minecraft
 * account UUID (never by name: offline accounts can take any name, and their
 * UUIDs are derived from the name, so they never match). Checked on every
 * edit action against the account playing right now, so switching accounts
 * changes it at once.
 */
final class SumoPermissions {
    /** The account "bringing". */
    static final UUID OWNER = UUID.fromString("ba14933e-a7ad-4816-8696-a788cc1b3c1f");
    static final String DENIED = "You do not have permission to edit the Sumo world.";

    private SumoPermissions() {
    }

    static boolean canEdit(UUID id) {
        return OWNER.equals(id);
    }

    static boolean canEdit(EntityPlayer p) {
        return p != null && p.getGameProfile() != null && canEdit(p.getGameProfile().getId());
    }

    /** Commands: only a player can edit (not the server console or command blocks). */
    static boolean canEdit(ICommandSender sender) {
        return sender instanceof EntityPlayer && canEdit((EntityPlayer) sender);
    }
}
