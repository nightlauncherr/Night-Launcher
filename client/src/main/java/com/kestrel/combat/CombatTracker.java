package com.kestrel.combat;

import com.kestrel.KestrelClient;
import com.kestrel.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * One place that follows your fights, shared by Hit Color, Low Health Alert
 * and Opponent Health Prediction (one attack listener, one tick listener).
 *
 * <p>Your hits are confirmed with {@link HitConfirmer}: you attacked a player
 * and the server's hurt status for that player arrived shortly after
 * ({@code MixinEntityLivingBaseQol} reports hurt statuses as they arrive). The
 * "current target" is the player you last attacked; it is dropped when they
 * die, leave render distance, or you haven't attacked them for
 * {@link #TARGET_TIMEOUT_MS}.
 *
 * <p>Read-only: nothing here sends packets or changes any game state.
 */
public final class CombatTracker {
    static final long TARGET_TIMEOUT_MS = 15_000;
    /** How long the low-health banner stays up. */
    public static final long ALERT_SHOW_MS = 2_500;

    private final KestrelClient client;
    private final HitConfirmer<Float> confirmer = new HitConfirmer<Float>();
    /** Entity id -> time its Hit Color tint started. */
    private final Map<Integer, Long> tints = new HashMap<Integer, Long>();

    private int targetId = -1;
    private String targetName = "";
    private long lastAttackMs;
    private HealthEstimator estimator;
    private final ThresholdAlert alert = new ThresholdAlert();
    private long alertAtMs = Long.MIN_VALUE / 4;
    private float targetHealth;
    private float targetMaxHealth = 20f;

    public CombatTracker(KestrelClient client) {
        this.client = client;
    }

    private ClientConfig.Qol cfg() {
        return client.config().qol;
    }

    private boolean anyActive() {
        ClientConfig.Qol q = cfg();
        return q.hitColor.enabled || q.lowHealthAlert.enabled || q.healthPrediction.enabled;
    }

    // ---- events ---------------------------------------------------------------

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || event.entityPlayer != mc.thePlayer || !anyActive()) return;
        if (!(event.target instanceof EntityPlayer) || event.target == mc.thePlayer) return;
        EntityPlayer t = (EntityPlayer) event.target;
        long now = System.currentTimeMillis();
        confirmer.attack(t.getEntityId(), now, expectedDamage(mc.thePlayer, t));
        if (t.getEntityId() != targetId) {
            targetId = t.getEntityId();
            targetName = t.getName();
            estimator = new HealthEstimator(cfg().healthPrediction.startingHealth);
            estimator.observe(t.getHealth(), t.getMaxHealth(), t.getAbsorptionAmount());
            alert.reset();
            alertAtMs = Long.MIN_VALUE / 4;
        }
        lastAttackMs = now;
    }

    /** A hurt status arrived for {@code e} (game thread; from the network handler). */
    public void onHurtStatus(EntityLivingBase e) {
        if (!anyActive()) return;
        long now = System.currentTimeMillis();
        int id = e.getEntityId();
        boolean isTarget = id == targetId && estimator != null;
        if (isTarget) estimator.hurt();
        Float damage = confirmer.hurt(id, now);
        if (damage == null) return; // not your hit (or a miss)
        if (cfg().hitColor.enabled) tints.put(id, now);
        if (isTarget) estimator.confirmedHit(damage);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        confirmer.expire(now);
        if (!tints.isEmpty()) {
            long dur = durationMs();
            Iterator<Long> it = tints.values().iterator();
            while (it.hasNext()) {
                if (now - it.next() >= dur) it.remove();
            }
        }
        if (targetId < 0) return;
        Entity e = mc.theWorld == null ? null : mc.theWorld.getEntityByID(targetId);
        if (!(e instanceof EntityPlayer) || e.isDead || ((EntityPlayer) e).getHealth() <= 0f
                || now - lastAttackMs > TARGET_TIMEOUT_MS || !anyActive()) {
            clearTarget();
            return;
        }
        EntityPlayer t = (EntityPlayer) e;
        targetHealth = t.getHealth();
        targetMaxHealth = t.getMaxHealth();
        estimator.observe(targetHealth, targetMaxHealth, t.getAbsorptionAmount());
        ClientConfig.LowHealthAlert la = cfg().lowHealthAlert;
        // Only health the server really sends can trigger the alert.
        if (la.enabled && estimator.trusted() && alert.update(targetHealth, la.threshold)) {
            alertAtMs = now;
            if (la.sound) {
                mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("note.pling"), 1.6f));
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) {
            confirmer.clear();
            tints.clear();
            clearTarget();
        }
    }

    private void clearTarget() {
        targetId = -1;
        targetName = "";
        estimator = null;
        alert.reset();
        alertAtMs = Long.MIN_VALUE / 4;
    }

    // ---- damage estimate ----------------------------------------------------------

    /** Expected damage of your attack on {@code t}, from what the client can see. */
    static float expectedDamage(EntityPlayerSP p, EntityPlayer t) {
        float attr = (float) p.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue();
        float ench = EnchantmentHelper.getModifierForCreature(p.getHeldItem(), t.getCreatureAttribute());
        boolean crit = p.fallDistance > 0f && !p.onGround && !p.isOnLadder() && !p.isInWater()
                && !p.isPotionActive(Potion.blindness) && p.ridingEntity == null;
        int prot = 0;
        ItemStack[] armor = t.inventory.armorInventory;
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null) {
                prot += DamageModel.protectionPoints(
                        EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, armor[i]));
            }
        }
        return DamageModel.afterDefences(DamageModel.raw(attr, ench, crit), t.isBlocking(), t.getTotalArmorValue(), prot);
    }

    // ---- read by rendering and HUD -------------------------------------------------

    private long durationMs() {
        return (long) (cfg().hitColor.duration * 1000f);
    }

    /** Hit Color tint strength for an entity right now (0 = none). */
    public float tintStrength(int entityId) {
        if (tints.isEmpty()) return 0f; // the common case: called for every entity drawn
        Long start = tints.get(entityId);
        if (start == null) return 0f;
        ClientConfig.HitColor hc = cfg().hitColor;
        if (!hc.enabled) return 0f;
        return HitTint.strength(System.currentTimeMillis() - start, durationMs(), hc.intensity, hc.fade);
    }

    public boolean hasTarget() {
        return targetId >= 0 && estimator != null;
    }

    public String targetName() {
        return targetName;
    }

    /** The target's health as the server reports it, if it has proven trustworthy. */
    public boolean targetHealthTrusted() {
        return hasTarget() && estimator.trusted();
    }

    public boolean targetHealthProvenUntrusted() {
        return hasTarget() && estimator.provenUntrusted();
    }

    public float targetHealth() {
        return targetHealth;
    }

    public float targetMaxHealth() {
        return targetMaxHealth;
    }

    public HealthEstimator estimator() {
        return estimator;
    }

    /** Milliseconds since the low-health alert fired, or a large value. */
    public long msSinceAlert() {
        long d = System.currentTimeMillis() - alertAtMs;
        return d < 0 ? Long.MAX_VALUE : d;
    }
}
