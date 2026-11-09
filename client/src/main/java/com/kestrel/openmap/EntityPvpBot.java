package com.kestrel.openmap;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * The Open Map opponent: a player-shaped living entity with a player's
 * physics, health, armor and inventory, driven by {@link PvpBrain}. Every input
 * goes through the vanilla rules a player is held to: 3.0 melee reach from
 * the eyes, the falling critical hit (x1.5), sprint knockback and losing sprint
 * on a sprint hit, item durability (sword per hit, rod per reel, armor per hit
 * taken), snowballs and planks used up one by one, block placement within 4.5
 * blocks against a solid face and never inside an entity, and health that only
 * comes back slowly (as with full food). It never has a golden apple.
 *
 * <p>It is never saved with the world and removes itself when no active Open
 * Map session owns it.
 */
public class EntityPvpBot extends EntityLivingBase {
    public static final String NAME = "Night Bot";

    // Hotbar: the brain's slots in order.
    static final int SWORD = 0;
    static final int PLANKS = 1;
    static final int ROD = 2;
    static final int SNOWBALL = 3;

    private OpenMapSession session;
    final ItemStack[] hotbar = new ItemStack[4];
    /** boots, leggings, chestplate, helmet (as a player's armor slots). */
    final ItemStack[] armor = new ItemStack[4];
    private int selected = SWORD;
    private int selectedTicks;
    /** What the client knows (from equipment packets). */
    private final ItemStack[] clientEquipment = new ItemStack[5];
    private final ItemStack[] inventoryView = new ItemStack[5];
    BotFishHook hook;
    private boolean sprintReady = true;
    private int regenTimer;
    int lastPlaceResult;

    /** Constructor used on the client (rendering only). */
    public EntityPvpBot(World world) {
        super(world);
        setSize(0.6F, 1.8F);
        setCustomNameTag(NAME);
        setAlwaysRenderNameTag(true);
    }

    EntityPvpBot(World world, OpenMapSession session) {
        this(world);
        this.session = session;
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.10000000149011612D);
        // A player's fist; the held sword adds its own modifier (+6) like a player's.
        getAttributeMap().registerAttribute(SharedMonsterAttributes.attackDamage).setBaseValue(1.0D);
    }

    @Override
    public float getEyeHeight() {
        return 1.62F;
    }

    /** The match kit: iron armor, iron sword, 64 oak planks, a fishing rod, 32 snowballs. No golden apple. */
    void giveKit() {
        hotbar[SWORD] = new ItemStack(Items.iron_sword);
        hotbar[PLANKS] = new ItemStack(Blocks.planks, 64, 0);
        hotbar[ROD] = new ItemStack(Items.fishing_rod);
        hotbar[SNOWBALL] = new ItemStack(Items.snowball, 32);
        armor[0] = new ItemStack(Items.iron_boots);
        armor[1] = new ItemStack(Items.iron_leggings);
        armor[2] = new ItemStack(Items.iron_chestplate);
        armor[3] = new ItemStack(Items.iron_helmet);
        selected = SWORD;
        setHealth(getMaxHealth());
    }

    int count(int slot) {
        ItemStack s = hotbar[slot];
        return s == null ? 0 : s.stackSize;
    }

    int selectedSlot() {
        return selected;
    }

    @Override
    public void onUpdate() {
        if (!worldObj.isRemote && (session == null || !session.owns(this))) {
            removeHook();
            setDead();
            return;
        }
        super.onUpdate();
    }

    @Override
    protected void updateEntityActionState() {
        if (session != null) session.driveBot(this);
    }

    // ---- Inputs.

    void idle(float faceYaw) {
        moveForward = 0F;
        moveStrafing = 0F;
        isJumping = false;
        if (isSprinting()) setSprinting(false);
        sprintReady = true;
        turn(faceYaw, 0F, 20F);
        applySpeed();
    }

    /** One tick of the brain's inputs, through the vanilla rules. */
    void apply(PvpBrain.Controls c, EntityPlayerMP target) {
        selectedTicks++;
        int want = c.slot.ordinal();
        if (want != selected && hotbar[want] != null) {
            selected = want; // a hotbar key press
            selectedTicks = 0;
        }
        turn(c.yaw, c.pitch, 360F);
        moveForward = MathHelper.clamp_float(c.forward, -1F, 1F);
        moveStrafing = MathHelper.clamp_float(c.strafe, -1F, 1F);
        isJumping = c.jump;
        if (c.forward <= 0F) sprintReady = true;
        if (isSprinting() && (c.forward < 0.8F || isCollidedHorizontally || !c.sprint)) setSprinting(false);
        if (!isSprinting() && c.sprint && sprintReady && c.forward >= 0.8F && !isCollidedHorizontally) setSprinting(true);
        applySpeed();
        if (c.attack) {
            swingItem();
            if (canReach(target)) attack(target);
        }
        // Right click uses the item in hand (not on the tick the slot changed).
        if (c.use && selectedTicks > 0) {
            if (selected == ROD) useRod();
            else if (selected == SNOWBALL) throwSnowball();
        }
        if (c.place) lastPlaceResult = placePlank(c.px, c.py, c.pz) ? 1 : -1;
        else lastPlaceResult = 0;
        naturalRegen();
    }

    private void applySpeed() {
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

    /** With full food a player heals half a heart every 4 seconds; the opponent gets the same, nothing more. */
    private void naturalRegen() {
        if (getHealth() < getMaxHealth() && getHealth() > 0 && ++regenTimer >= 80) {
            regenTimer = 0;
            heal(1.0F);
        }
    }

    // ---- Melee.

    private Vec3 look() {
        float yawRad = -rotationYaw * 0.017453292F - (float) Math.PI;
        float pitchRad = -rotationPitch * 0.017453292F;
        float cosPitch = -MathHelper.cos(pitchRad);
        return new Vec3(MathHelper.sin(yawRad) * cosPitch, MathHelper.sin(pitchRad), MathHelper.cos(yawRad) * cosPitch);
    }

    /** A player's reach test: the look ray from the eyes hits the target's box within 3 blocks. */
    boolean canReach(EntityLivingBase target) {
        if (target == null || target.isDead || !canEntityBeSeen(target)) return false;
        Vec3 eye = new Vec3(posX, posY + getEyeHeight(), posZ);
        Vec3 l = look();
        double r = PvpBrain.REACH;
        Vec3 end = eye.addVector(l.xCoord * r, l.yCoord * r, l.zCoord * r);
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        if (box.isVecInside(eye)) return true;
        MovingObjectPosition hit = box.calculateIntercept(eye, end);
        return hit != null && eye.distanceTo(hit.hitVec) <= r;
    }

    /** EntityPlayer#attackTargetEntityWithCurrentItem against a living target. */
    private void attack(EntityLivingBase target) {
        float damage = (float) getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue();
        int knockback = isSprinting() ? 1 : 0;
        boolean crit = fallDistance > 0.0F && !onGround && !isOnLadder() && !isInWater() && ridingEntity == null;
        if (crit) damage *= 1.5F;
        double mx = target.motionX;
        double my = target.motionY;
        double mz = target.motionZ;
        boolean hit = target.attackEntityFrom(new BotDamage(this), damage);
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
        if (crit && worldObj instanceof WorldServer) {
            ((WorldServer) worldObj).getEntityTracker().func_151248_b(this, new S0BPacketAnimation(target, 4));
        }
        setLastAttacker(target);
        ItemStack held = hotbar[selected];
        if (held != null && held.getItem() instanceof ItemSword) {
            held.damageItem(1, this); // a sword loses 1 durability per hit
            if (held.stackSize <= 0) hotbar[selected] = null;
        }
        if (session != null) session.onBotHit(knockback > 0, damage, crit);
    }

    // ---- Items.

    private void useRod() {
        ItemStack rod = hotbar[ROD];
        if (rod == null) return;
        if (hook != null && !hook.isDead) {
            int wear = hook.retract();
            hook = null;
            rod.damageItem(wear, this);
            if (rod.stackSize <= 0) hotbar[ROD] = null;
            swingItem();
        } else {
            worldObj.playSoundAtEntity(this, "random.bow", 0.5F, 0.4F / (rand.nextFloat() * 0.4F + 0.8F));
            hook = new BotFishHook(worldObj, this);
            worldObj.spawnEntityInWorld(hook);
            swingItem();
            if (session != null) session.onBotRodCast();
        }
    }

    void removeHook() {
        if (hook != null) {
            hook.setDead();
            hook = null;
        }
    }

    private void throwSnowball() {
        ItemStack s = hotbar[SNOWBALL];
        if (s == null || s.stackSize <= 0) return;
        if (--s.stackSize <= 0) hotbar[SNOWBALL] = null; // used up, one per throw
        worldObj.playSoundAtEntity(this, "random.bow", 0.5F, 0.4F / (rand.nextFloat() * 0.4F + 0.8F));
        worldObj.spawnEntityInWorld(new BotSnowball(worldObj, this));
        swingItem();
        if (session != null) session.onBotSnowball();
    }

    /**
     * Right-click a plank into place at (x, y, z), as a survival player can:
     * within 4.5 blocks of the eyes, looking that way, against a solid face, the
     * spot replaceable, inside the world border and not inside any entity.
     */
    private boolean placePlank(int x, int y, int z) {
        ItemStack planks = hotbar[PLANKS];
        if (selected != PLANKS || planks == null || planks.stackSize <= 0 || selectedTicks <= 0) return false;
        if (y < 1 || y > 254) return false;
        BlockPos pos = new BlockPos(x, y, z);
        double ex = x + 0.5 - posX;
        double ey = y + 0.5 - (posY + getEyeHeight());
        double ez = z + 0.5 - posZ;
        double dist = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (dist > PvpBrain.PLACE_REACH) return false;
        Vec3 l = look();
        double cos = (l.xCoord * ex + l.yCoord * ey + l.zCoord * ez) / Math.max(1e-6, dist);
        if (cos < Math.cos(Math.toRadians(55))) return false; // not looking at it
        if (!worldObj.getWorldBorder().contains(pos)) return false;
        IBlockState old = worldObj.getBlockState(pos);
        if (!old.getBlock().isReplaceable(worldObj, pos)) return false;
        boolean support = false;
        for (EnumFacing f : EnumFacing.values()) {
            if (worldObj.getBlockState(pos.offset(f)).getBlock().getMaterial().isSolid()) {
                support = true;
                break;
            }
        }
        if (!support) return false;
        if (!worldObj.canBlockBePlaced(Blocks.planks, pos, false, EnumFacing.UP, this, planks)) return false;
        if (session != null) session.recordChange(pos, old);
        IBlockState placed = Blocks.planks.getDefaultState();
        if (!worldObj.setBlockState(pos, placed, 3)) return false;
        Block.SoundType sound = Blocks.planks.stepSound;
        worldObj.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, sound.getPlaceSound(), (sound.getVolume() + 1.0F) / 2.0F,
                sound.getFrequency() * 0.8F);
        if (--planks.stackSize <= 0) hotbar[PLANKS] = null;
        swingItem();
        if (session != null) session.onBotPlaced();
        return true;
    }

    // ---- Armor wears like a player's.

    @Override
    protected void damageArmor(float damage) {
        damage /= 4.0F;
        if (damage < 1.0F) damage = 1.0F;
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && armor[i].getItem() instanceof ItemArmor) {
                armor[i].damageItem((int) damage, this);
                if (armor[i].stackSize <= 0) armor[i] = null;
            }
        }
    }

    // ---- Equipment (also what the client draws).

    @Override
    public ItemStack getHeldItem() {
        return worldObj.isRemote ? clientEquipment[0] : hotbar[selected];
    }

    @Override
    public ItemStack getEquipmentInSlot(int slot) {
        if (worldObj.isRemote) return slot >= 0 && slot < 5 ? clientEquipment[slot] : null;
        if (slot == 0) return hotbar[selected];
        return slot >= 1 && slot <= 4 ? armor[slot - 1] : null;
    }

    @Override
    public ItemStack getCurrentArmor(int slot) {
        return getEquipmentInSlot(slot + 1);
    }

    @Override
    public void setCurrentItemOrArmor(int slot, ItemStack stack) {
        if (worldObj.isRemote && slot >= 0 && slot < 5) clientEquipment[slot] = stack;
    }

    @Override
    public ItemStack[] getInventory() {
        for (int i = 0; i < 5; i++) inventoryView[i] = getEquipmentInSlot(i);
        return inventoryView;
    }

    // ---- Never saved with the world.

    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;
    }

    @Override
    public boolean writeMountToNBT(NBTTagCompound tag) {
        return false;
    }

    @Override
    public void setDead() {
        super.setDead();
        if (!worldObj.isRemote && hook != null) {
            hook.setDead();
            hook = null;
        }
    }

    /** A player-type hit (never scaled by the world's difficulty, like a player's). */
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
