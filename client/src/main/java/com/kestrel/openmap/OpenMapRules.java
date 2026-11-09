package com.kestrel.openmap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;

/**
 * The one rule Open Map adds to vanilla: a 0-damage projectile hit (rod hook,
 * snowball) on a player has the same effect it has on any other living entity
 * in vanilla, so the opponent's items work on the player exactly like the
 * player's items work on the opponent. That is the EntityLivingBase hit path
 * for 0 damage: ignored during the second half of hurt immunity, otherwise the
 * hurt flash and sound, fresh hurt immunity and the standard 0.4 knockback away
 * from the thrower. No damage, ever.
 */
final class OpenMapRules {
    /** EntityLivingBase#lastDamage (MCP name, SRG name). */
    private static Field lastDamage;

    private OpenMapRules() {
    }

    static boolean projectileHit(EntityPlayer player, Entity thrower) {
        if (player == null || player.isDead || player.capabilities.disableDamage || player.getHealth() <= 0) return false;
        if (player.hurtResistantTime > player.maxHurtResistantTime / 2.0F) return false;
        setLastDamage(player, 0.0F);
        player.hurtResistantTime = player.maxHurtResistantTime;
        player.hurtTime = player.maxHurtTime = 10;
        player.attackedAtYaw = 0.0F;
        player.worldObj.setEntityState(player, (byte) 2);
        if (thrower != null) {
            double dx = thrower.posX - player.posX;
            double dz = thrower.posZ - player.posZ;
            while (dx * dx + dz * dz < 1.0E-4D) {
                dx = (Math.random() - Math.random()) * 0.01D;
                dz = (Math.random() - Math.random()) * 0.01D;
            }
            player.attackedAtYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI - player.rotationYaw);
            player.knockBack(thrower, 0.0F, dx, dz);
        }
        player.velocityChanged = true; // the entity tracker sends the new motion to the player
        return true;
    }

    private static void setLastDamage(EntityLivingBase e, float v) {
        try {
            if (lastDamage == null) lastDamage = ReflectionHelper.findField(EntityLivingBase.class, "lastDamage", "field_110153_bc");
            lastDamage.setFloat(e, v);
        } catch (RuntimeException | IllegalAccessException ex) {
            // Without it a sword hit right after may count as a weaker repeat hit; nothing else changes.
        }
    }

    static void rodLanded(EntityPvpBot bot) {
        OpenMapServer.get().onBotRodHit(bot);
    }

    static void snowballLanded(EntityPvpBot bot) {
        OpenMapServer.get().onBotSnowballHit(bot);
    }
}
