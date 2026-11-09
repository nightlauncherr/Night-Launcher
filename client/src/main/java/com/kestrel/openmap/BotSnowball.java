package com.kestrel.openmap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/**
 * The opponent's snowball: a vanilla snowball (same speed, drag, gravity and
 * aim spread), except that hitting a player gives the same 0-damage hurt and
 * knockback a player's snowball gives the opponent. In vanilla 1.8.9 a
 * 0-damage hit on a player is ignored outright, so without this the
 * opponent's snowballs would do nothing while the player's push it back.
 */
public class BotSnowball extends EntitySnowball {
    public BotSnowball(World world) {
        super(world);
    }

    BotSnowball(World world, EntityLivingBase thrower) {
        super(world, thrower);
    }

    @Override
    protected void onImpact(MovingObjectPosition hit) {
        if (hit.entityHit instanceof EntityPlayer) {
            if (!worldObj.isRemote) {
                if (OpenMapRules.projectileHit((EntityPlayer) hit.entityHit, getThrower()) && getThrower() instanceof EntityPvpBot) {
                    OpenMapRules.snowballLanded((EntityPvpBot) getThrower());
                }
            }
            for (int j = 0; j < 8; ++j) {
                worldObj.spawnParticle(EnumParticleTypes.SNOWBALL, posX, posY, posZ, 0.0D, 0.0D, 0.0D);
            }
            if (!worldObj.isRemote) setDead();
            return;
        }
        super.onImpact(hit);
    }
}
