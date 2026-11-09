package com.kestrel.openmap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.List;

/**
 * The opponent's fishing hook. Vanilla's hook can only belong to a player, so
 * this is the same thing for the opponent: cast at speed 1.5 from the eyes with
 * the vanilla spread, in the air 0.04 gravity and 0.92 drag, it hooks the first
 * entity it touches (not its own caster for 5 ticks), stops on blocks, and
 * reeling a hooked entity pulls it toward the caster exactly as vanilla does.
 * It breaks if the caster drops the rod or gets more than 32 blocks away.
 */
public class BotFishHook extends Entity {
    private static final int ANGLER_ID = 16;

    private EntityPvpBot angler;
    Entity caught;
    boolean inGround;
    int ticksInAir;

    public BotFishHook(World world) {
        super(world);
        setSize(0.25F, 0.25F);
        ignoreFrustumCheck = true;
    }

    BotFishHook(World world, EntityPvpBot angler) {
        this(world);
        this.angler = angler;
        dataWatcher.updateObject(ANGLER_ID, Integer.valueOf(angler.getEntityId()));
        setLocationAndAngles(angler.posX, angler.posY + angler.getEyeHeight(), angler.posZ, angler.rotationYaw, angler.rotationPitch);
        posX -= MathHelper.cos(rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
        posY -= 0.10000000149011612D;
        posZ -= MathHelper.sin(rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
        setPosition(posX, posY, posZ);
        float f = 0.4F;
        double mx = -MathHelper.sin(rotationYaw / 180.0F * (float) Math.PI) * MathHelper.cos(rotationPitch / 180.0F * (float) Math.PI) * f;
        double mz = MathHelper.cos(rotationYaw / 180.0F * (float) Math.PI) * MathHelper.cos(rotationPitch / 180.0F * (float) Math.PI) * f;
        double my = -MathHelper.sin(rotationPitch / 180.0F * (float) Math.PI) * f;
        cast(mx, my, mz, 1.5F, 1.0F);
    }

    /** EntityFishHook#handleHookCasting. */
    private void cast(double x, double y, double z, float speed, float spread) {
        float len = MathHelper.sqrt_double(x * x + y * y + z * z);
        x = x / len + rand.nextGaussian() * 0.007499999832361937D * spread;
        y = y / len + rand.nextGaussian() * 0.007499999832361937D * spread;
        z = z / len + rand.nextGaussian() * 0.007499999832361937D * spread;
        motionX = x * speed;
        motionY = y * speed;
        motionZ = z * speed;
    }

    @Override
    protected void entityInit() {
        dataWatcher.addObject(ANGLER_ID, Integer.valueOf(0));
    }

    /** The caster (also on the client, for drawing the line). */
    public EntityPvpBot angler() {
        if (angler == null && worldObj.isRemote) {
            Entity e = worldObj.getEntityByID(dataWatcher.getWatchableObjectInt(ANGLER_ID));
            if (e instanceof EntityPvpBot) angler = (EntityPvpBot) e;
        }
        return angler;
    }

    boolean hooked() {
        return caught != null && !caught.isDead;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (worldObj.isRemote) return; // the client just shows the server's position
        ItemStack held = angler == null ? null : angler.getHeldItem();
        if (angler == null || angler.isDead || held == null || held.getItem() != Items.fishing_rod
                || getDistanceSqToEntity(angler) > 1024.0D) {
            setDead();
            if (angler != null && angler.hook == this) angler.hook = null;
            return;
        }
        if (caught != null) {
            if (!caught.isDead) {
                posX = caught.posX;
                posY = caught.getEntityBoundingBox().minY + caught.height * 0.8D;
                posZ = caught.posZ;
                setPosition(posX, posY, posZ);
                return;
            }
            caught = null;
        }
        if (inGround) return;
        ticksInAir++;
        Vec3 from = new Vec3(posX, posY, posZ);
        Vec3 to = new Vec3(posX + motionX, posY + motionY, posZ + motionZ);
        MovingObjectPosition blockHit = worldObj.rayTraceBlocks(from, to);
        if (blockHit != null) to = new Vec3(blockHit.hitVec.xCoord, blockHit.hitVec.yCoord, blockHit.hitVec.zCoord);
        Entity hitEntity = null;
        double best = 0;
        List<Entity> list = worldObj.getEntitiesWithinAABBExcludingEntity(this,
                getEntityBoundingBox().addCoord(motionX, motionY, motionZ).expand(1.0D, 1.0D, 1.0D));
        for (Entity e : list) {
            if (!e.canBeCollidedWith() || (e == angler && ticksInAir < 5)) continue;
            AxisAlignedBB box = e.getEntityBoundingBox().expand(0.3D, 0.3D, 0.3D);
            MovingObjectPosition m = box.calculateIntercept(from, to);
            if (m == null) continue;
            double d = from.squareDistanceTo(m.hitVec);
            if (hitEntity == null || d < best) {
                hitEntity = e;
                best = d;
            }
        }
        if (hitEntity != null) {
            if (hitEntity instanceof EntityPlayer) {
                // A 0-damage hit on a player: the same hurt and knockback a player's rod gives the opponent.
                if (OpenMapRules.projectileHit((EntityPlayer) hitEntity, angler)) {
                    caught = hitEntity;
                    if (angler != null) OpenMapRules.rodLanded(angler);
                }
            } else if (hitEntity.attackEntityFrom(DamageSource.causeThrownDamage(this, angler), 0.0F)) {
                caught = hitEntity;
            }
            if (caught != null) return;
        } else if (blockHit != null) {
            inGround = true;
            motionX = motionY = motionZ = 0;
            return;
        }
        moveEntity(motionX, motionY, motionZ);
        float drag = onGround || isCollidedHorizontally ? 0.5F : 0.92F;
        if (isInWater()) {
            drag *= 0.9F;
            motionY += 0.04D;
            motionY *= 0.8D;
        } else {
            motionY -= 0.03999999910593033D;
        }
        motionX *= drag;
        motionY *= drag;
        motionZ *= drag;
        if (onGround) inGround = true;
    }

    /**
     * Reel in (EntityFishHook#handleHookRetraction): pulls a hooked entity
     * toward the caster. Returns the rod wear: 3 if something was hooked, 2 if
     * stuck in the ground, else 0.
     */
    int retract() {
        int wear = 0;
        if (caught != null && angler != null) {
            double dx = angler.posX - posX;
            double dy = angler.posY - posY;
            double dz = angler.posZ - posZ;
            double d = MathHelper.sqrt_double(dx * dx + dy * dy + dz * dz);
            caught.motionX += dx * 0.1D;
            caught.motionY += dy * 0.1D + MathHelper.sqrt_double(d) * 0.08D;
            caught.motionZ += dz * 0.1D;
            caught.velocityChanged = true; // sent to the player by the entity tracker
            if (caught instanceof EntityPlayerMP) caught.isAirBorne = true;
            wear = 3;
        } else if (inGround) {
            wear = 2;
        }
        setDead();
        return wear;
    }

    @Override
    public boolean isInRangeToRenderDist(double distance) {
        return distance < 4096.0D;
    }

    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
    }
}
