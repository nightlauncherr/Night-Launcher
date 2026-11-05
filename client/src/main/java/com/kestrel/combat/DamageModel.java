package com.kestrel.combat;

/**
 * Minecraft 1.8.9's melee damage rules, from values a client can see. Pure and
 * unit-tested; checked against the 1.8.9 bytecode of
 * {@code EntityPlayer.attackTargetEntityWithCurrentItem},
 * {@code EntityPlayer.damageEntity} and {@code EntityLivingBase.applyArmorCalculations} /
 * {@code applyPotionDamageCalculations}.
 *
 * <p>Not visible to a client, so not included: the target's Resistance
 * effect, any server-side damage changes (plugins, game modes), and which
 * random roll the Protection enchantment made (its expected value is used).
 */
public final class DamageModel {
    private DamageModel() {
    }

    /**
     * Damage your hit deals before the target's defences.
     *
     * @param attackAttribute your attack damage attribute (base 1 + weapon + Strength/Weakness)
     * @param enchantBonus    Sharpness/Smite/Bane bonus for this target
     * @param critical        a falling critical hit (x1.5 on the attribute part)
     */
    public static float raw(float attackAttribute, float enchantBonus, boolean critical) {
        float f = attackAttribute;
        if (critical) f *= 1.5f;
        return Math.max(0f, f + enchantBonus);
    }

    /**
     * Expected damage after the target's defences.
     *
     * @param blocking          target is blocking with a sword
     * @param armor             target's total armour points (0..20, from the armour it wears)
     * @param protectionPoints  sum of the target's Protection enchantment points (see {@link #protectionPoints})
     */
    public static float afterDefences(float raw, boolean blocking, int armor, int protectionPoints) {
        float d = raw;
        if (blocking && d > 0f) d = (1f + d) * 0.5f;
        int a = Math.max(0, Math.min(25, armor));
        d = d * (25 - a) / 25f;
        return d * expectedProtectionFactor(protectionPoints);
    }

    /**
     * Protection enchantment points of one armour piece: vanilla's
     * {@code floor((6 + level^2) / 3 * 0.75)} for Protection (all damage).
     */
    public static int protectionPoints(int level) {
        if (level <= 0) return 0;
        return (int) Math.floor((6 + level * level) / 3.0f * 0.75f);
    }

    /**
     * Expected multiplier from Protection. Vanilla caps the summed points at 25,
     * then rolls {@code ((i + 1) >> 1) + random(0..(i >> 1))}, caps that at 20 and
     * scales damage by {@code (25 - k) / 25}.
     */
    public static float expectedProtectionFactor(int points) {
        int i = Math.max(0, Math.min(25, points));
        if (i == 0) return 1f;
        int base = (i + 1) >> 1;
        int span = (i >> 1) + 1;
        float sum = 0f;
        for (int r = 0; r < span; r++) {
            int k = Math.min(20, base + r);
            sum += (25 - k) / 25f;
        }
        return sum / span;
    }
}
