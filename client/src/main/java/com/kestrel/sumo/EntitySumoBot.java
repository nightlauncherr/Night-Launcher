package com.kestrel.sumo;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * The Sumo opponent: a player-shaped living entity driven by {@link SumoBrain}
 * on the integrated server. It moves with player physics (base speed 0.1,
 * +30% sprinting, 0.02/0.026 air control, 0.6 step, the vanilla sprint-jump
 * boost) and attacks exactly like a player's fist: 3.0 reach from the eyes
 * along its look direction, the vanilla sprint knockback bonus, losing sprint
 * on a sprint hit, and the same velocity packet a player hit sends.
 *
 * <p>It is never saved with the world, and removes itself when no active Sumo
 * session owns it (for example a copy left by a crash).
 */
public class EntitySumoBot extends EntityLivingBase {
    public static final String NAME = "Night Bot";
    private static final ItemStack[] NO_ITEMS = new ItemStack[5];

    private SumoSession session;
    /** False after a sprint hit until W is released, like a player's sprint reset. */
    private boolean sprintReady = true;

    /** Constructor used when the entity is spawned on the client (rendering only). */
    public EntitySumoBot(World world) {
        super(world);
        setSize(0.6F, 1.8F);
        setCustomNameTag(NAME);
        setAlwaysRenderNameTag(true);
    }

    EntitySumoBot(World world, SumoSession session) {
        this(world);
        this.session = session;
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        // A player's base walking speed.
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.10000000149011612D);
    }

    @Override
    public float getEyeHeight() {
        return 1.62F; // a player's
    }

    @Override
    public void onUpdate() {
        if (!worldObj.isRemote && (session == null || !session.owns(this))) {
            setDead();
            return;
        }
        super.onUpdate();
    }

    /** Called from {@link EntityLivingBase#onLivingUpdate()} on the server, before movement. */
    @Override
    protected void updateEntityActionState() {
        if (session != null) session.driveBot(this);
    }

    /** Hold still (countdown and between rounds). */
    void idle(float faceYaw) {
        moveForward = 0F;
        moveStrafing = 0F;
        isJumping = false;
        if (isSprinting()) setSprinting(false);
        sprintReady = true;
        turn(faceYaw, 0F, 20F);
        applySpeed();
    }

    /** Play one tick of inputs through vanilla movement and combat rules. */
    void apply(SumoBrain.Controls c, EntityLivingBase target, SumoBrain brain) {
        turn(c.yaw, c.pitch, 360F); // the brain already limited the turn rate
        moveForward = c.forward;
        moveStrafing = c.strafe;
        isJumping = c.jump;
        // A player only sprints while moving forward and not blocked.
        if (c.forward <= 0F) sprintReady = true;
        if (isSprinting() && (c.forward < 0.8F || isCollidedHorizontally || !c.sprint)) setSprinting(false);
        if (!isSprinting() && c.sprint && sprintReady && c.forward >= 0.8F && !isCollidedHorizontally) setSprinting(true);
        applySpeed();
        if (c.attack) {
            swingItem();
            if (canReach(target)) attack(target, brain);
        }
    }

    private void applySpeed() {
        // The same as EntityPlayer#onLivingUpdate: ground speed from the attribute
        // (which includes the sprint boost), air control 0.02 (+30% sprinting).
        setAIMoveSpeed((float) getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue());
        jumpMovementFactor = isSprinting() ? 0.026F : 0.02F;
    }

    private void turn(float yaw, float pitch, float maxStep) {
        float dy = MathHelper.wrapAngleTo180_float(yaw - rotationYaw);
        dy = MathHelper.clamp_float(dy, -maxStep, maxStep);
        rotationYaw += dy;
        rotationPitch = MathHelper.clamp_float(pitch, -90F, 90F);
        rotationYawHead = rotationYaw;
    }

    /** A player's reach test: the look ray from the eyes hits the target's box within 3 blocks. */
    boolean canReach(EntityLivingBase target) {
        if (target == null || target.isDead || !canEntityBeSeen(target)) return false;
        Vec3 eye = new Vec3(posX, posY + getEyeHeight(), posZ);
        // Look vector as Entity#getVectorForRotation computes it (avoids client-only helpers).
        float yawRad = -rotationYaw * 0.017453292F - (float) Math.PI;
        float pitchRad = -rotationPitch * 0.017453292F;
        float cosPitch = -MathHelper.cos(pitchRad);
        double lx = MathHelper.sin(yawRad) * cosPitch;
        double ly = MathHelper.sin(pitchRad);
        double lz = MathHelper.cos(yawRad) * cosPitch;
        double r = SumoBrain.REACH;
        Vec3 end = eye.addVector(lx * r, ly * r, lz * r);
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        if (box.isVecInside(eye)) return true;
        MovingObjectPosition hit = box.calculateIntercept(eye, end);
        return hit != null && eye.distanceTo(hit.hitVec) <= r;
    }

    /** EntityPlayer#attackTargetEntityWithCurrentItem for an empty hand. */
    private void attack(EntityLivingBase target, SumoBrain brain) {
        int knockback = isSprinting() ? 1 : 0;
        double mx = target.motionX;
        double my = target.motionY;
        double mz = target.motionZ;
        boolean hit = target.attackEntityFrom(new BotDamage(this), 1.0F);
        if (!hit) return;
        if (knockback > 0) {
            float yawRad = rotationYaw * (float) Math.PI / 180.0F;
            target.addVelocity(-MathHelper.sin(yawRad) * knockback * 0.5F, 0.1D, MathHelper.cos(yawRad) * knockback * 0.5F);
            motionX *= 0.6D;
            motionZ *= 0.6D;
            setSprinting(false);
            sprintReady = false;
        }
        if (target instanceof EntityPlayerMP && target.velocityChanged) {
            ((EntityPlayerMP) target).playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(target));
            target.velocityChanged = false;
            target.motionX = mx;
            target.motionY = my;
            target.motionZ = mz;
        }
        brain.onHitLanded(knockback > 0);
        if (session != null) session.onBotHit();
    }

    // ---- Never saved with the world: a fresh opponent is spawned per session.

    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;
    }

    @Override
    public boolean writeMountToNBT(NBTTagCompound tag) {
        return false;
    }

    // ---- No equipment.

    @Override
    public ItemStack getHeldItem() {
        return null;
    }

    @Override
    public ItemStack getEquipmentInSlot(int slot) {
        return null;
    }

    @Override
    public ItemStack getCurrentArmor(int slot) {
        return null;
    }

    @Override
    public void setCurrentItemOrArmor(int slot, ItemStack stack) {
    }

    @Override
    public ItemStack[] getInventory() {
        return NO_ITEMS;
    }

    /** Damage from the opponent's fist; never reduced by the world's difficulty (like a player's). */
    static final class BotDamage extends EntityDamageSource {
        BotDamage(Entity bot) {
            super("player", bot);
        }

        @Override
        public boolean isDifficultyScaled() {
            return false;
        }
    }
}
