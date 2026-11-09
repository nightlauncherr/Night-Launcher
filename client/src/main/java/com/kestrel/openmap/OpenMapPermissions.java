package com.kestrel.openmap;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;

import java.util.UUID;

/**
 * Who may use the Open Map world-edit controls: only the map's owner, by
 * Minecraft account UUID (never by name). Checked against the account playing
 * right now on every privileged action. Separate from Sumo's check.
 */
final class OpenMapPermissions {
    /** The account "bringing". */
    static final UUID OWNER = UUID.fromString("ba14933e-a7ad-4816-8696-a788cc1b3c1f");
    static final String DENIED = "You do not have permission to edit the Open Map world.";

    private OpenMapPermissions() {
    }

    static boolean canEdit(UUID id) {
        return OWNER.equals(id);
    }

    static boolean canEdit(EntityPlayer p) {
        return p != null && p.getGameProfile() != null && canEdit(p.getGameProfile().getId());
    }

    static boolean canEdit(ICommandSender sender) {
        return sender instanceof EntityPlayer && canEdit((EntityPlayer) sender);
    }
}
