package com.kestrel.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CombatLogicTest {
    private static final float EPS = 1e-4f;

    // ---- HitTint

    @Test
    public void tintFadesFromIntensityToZero() {
        assertEquals(0.5f, HitTint.strength(0, 500, 0.5f, true), EPS);
        assertEquals(0.5f * 0.25f, HitTint.strength(250, 500, 0.5f, true), EPS);
        assertEquals(0f, HitTint.strength(500, 500, 0.5f, true), EPS);
        assertEquals(0f, HitTint.strength(-1, 500, 0.5f, true), EPS);
    }

    @Test
    public void tintWithoutFadeHoldsThenStops() {
        assertEquals(0.4f, HitTint.strength(499, 500, 0.4f, false), EPS);
        assertEquals(0f, HitTint.strength(500, 500, 0.4f, false), EPS);
    }

    // ---- HitConfirmer

    @Test
    public void hurtSoonAfterYourAttackIsYours() {
        HitConfirmer<String> c = new HitConfirmer<String>();
        c.attack(7, 1000, "snap");
        assertEquals("snap", c.hurt(7, 1120));
        // Consumed: a second hurt (fall damage, another player) is not yours.
        assertNull(c.hurt(7, 1200));
    }

    @Test
    public void missesOtherTargetsAndLateHurtsAreNotYours() {
        HitConfirmer<String> c = new HitConfirmer<String>();
        assertNull(c.hurt(7, 1000)); // never attacked
        c.attack(7, 1000, "a");
        assertNull(c.hurt(8, 1100)); // a different player
        assertNull(c.hurt(7, 1000 + HitConfirmer.WINDOW_MS + 1)); // too late: the attack missed
        c.attack(9, 5000, "b");
        c.expire(5000 + HitConfirmer.WINDOW_MS + 1);
        assertEquals(0, c.size());
    }

    // ---- ThresholdAlert

    @Test
    public void alertFiresOnceOnCrossingAndRearmsAfterRecovery() {
        ThresholdAlert a = new ThresholdAlert();
        assertFalse(a.update(20f, 5f));
        assertTrue(a.update(5f, 5f)); // reaches the threshold
        assertFalse(a.update(4f, 5f)); // still low: no repeat
        assertFalse(a.update(3f, 5f));
        assertFalse(a.update(8f, 5f)); // recovered
        assertTrue(a.update(2f, 5f)); // crosses again
        a.reset();
        assertTrue(a.update(2f, 5f)); // new target already low: alerts once
    }

    // ---- DamageModel

    @Test
    public void rawDamageMatchesVanilla() {
        // Diamond sword in 1.8.9: attack attribute 8 (base 1 + sword 7).
        assertEquals(8f, DamageModel.raw(8f, 0f, false), EPS);
        assertEquals(12f, DamageModel.raw(8f, 0f, true), EPS);
        // Sharpness II: +2.5, not multiplied by the critical hit.
        assertEquals(14.5f, DamageModel.raw(8f, 2.5f, true), EPS);
    }

    @Test
    public void armourAndBlockingReduceDamage() {
        // Full diamond armour = 20 points: 80% off.
        assertEquals(7f * 5f / 25f, DamageModel.afterDefences(7f, false, 20, 0), EPS);
        // Blocking halves (1 + damage).
        assertEquals((1f + 7f) * 0.5f, DamageModel.afterDefences(7f, true, 0, 0), EPS);
    }

    @Test
    public void protectionMatchesVanillaRolls() {
        assertEquals(0, DamageModel.protectionPoints(0));
        assertEquals(1, DamageModel.protectionPoints(1)); // floor(7/3*0.75) = 1
        assertEquals(2, DamageModel.protectionPoints(2)); // floor(10/3*0.75) = 2
        assertEquals(3, DamageModel.protectionPoints(3)); // floor(15/3*0.75) = 3
        assertEquals(5, DamageModel.protectionPoints(4)); // floor(22/3*0.75) = 5
        assertEquals(1f, DamageModel.expectedProtectionFactor(0), EPS);
        // i = 4: k = 2 + {0,1,2} -> (23 + 22 + 21) / 75
        assertEquals((23f + 22f + 21f) / 75f, DamageModel.expectedProtectionFactor(4), EPS);
        // Capped at 25 points and k at 20.
        assertEquals(DamageModel.expectedProtectionFactor(25), DamageModel.expectedProtectionFactor(40), EPS);
        assertTrue(DamageModel.expectedProtectionFactor(25) >= 5f / 25f);
    }

    // ---- HealthEstimator

    @Test
    public void startingBelowFullIsConfirmedOnlyOnceItChanges() {
        HealthEstimator h = new HealthEstimator(20f);
        h.observe(13f, 20f, 0f);
        assertEquals(HealthEstimator.Source.REPORTED, h.source());
        h.observe(14f, 20f, 0f); // regenerated: the value is live
        assertEquals(HealthEstimator.Source.CONFIRMED, h.source());
        assertEquals(14f, h.health(), EPS);
    }

    @Test
    public void realServerHealthIsConfirmed() {
        HealthEstimator h = new HealthEstimator(20f);
        h.observe(20f, 20f, 0f);
        assertEquals(HealthEstimator.Source.REPORTED, h.source());
        h.hurt();
        h.confirmedHit(5f);
        h.observe(15f, 20f, 0f);
        assertEquals(HealthEstimator.Source.CONFIRMED, h.source());
        assertEquals(15f, h.health(), EPS);
    }

    @Test
    public void hiddenHealthFallsBackToAnEstimate() {
        HealthEstimator h = new HealthEstimator(20f);
        h.observe(20f, 20f, 0f);
        h.hurt();
        h.confirmedHit(4.5f);
        h.observe(20f, 20f, 0f); // server never changes it
        assertEquals(HealthEstimator.Source.ESTIMATED, h.source());
        assertEquals(15.5f, h.health(), EPS);
        h.hurt();
        h.confirmedHit(4f);
        assertTrue(h.provenUntrusted());
        assertEquals(11.5f, h.health(), EPS);
    }

    @Test
    public void estimateUsesAbsorptionFirstAndNeverGoesNegative() {
        HealthEstimator h = new HealthEstimator(20f);
        h.observe(20f, 20f, 4f);
        h.hurt();
        h.confirmedHit(3f);
        assertEquals(20f, h.health(), EPS);
        assertEquals(1f, h.absorption(), EPS);
        h.confirmedHit(100f);
        assertEquals(0f, h.health(), EPS);
    }

    @Test
    public void fakeConstantHealthUsesTheAssumption() {
        HealthEstimator h = new HealthEstimator(20f);
        h.observe(1f, 20f, 0f); // a fixed fake value below max is not trusted
        assertEquals(HealthEstimator.Source.REPORTED, h.source());
        assertFalse(h.trusted());
        h.hurt();
        h.confirmedHit(6f);
        h.observe(1f, 20f, 0f);
        assertEquals(HealthEstimator.Source.ESTIMATED, h.source());
        assertEquals(14f, h.health(), EPS); // from the assumption, not from the fake 1.0
        h.hurt();
        assertTrue(h.provenUntrusted());
        assertEquals(14f, h.health(), EPS);
        HealthEstimator nan = new HealthEstimator(20f);
        nan.observe(Float.NaN, 20f, 0f);
        assertEquals(HealthEstimator.Source.UNAVAILABLE, nan.source());
        nan.hurt();
        nan.hurt();
        assertEquals(HealthEstimator.Source.UNAVAILABLE, nan.source());
    }

    // ---- BlockCounting

    @Test
    public void blockCategoriesAndFilter() {
        assertEquals(BlockCounting.Category.WOOL, BlockCounting.category("minecraft:wool"));
        assertEquals(BlockCounting.Category.CLAY, BlockCounting.category("minecraft:stained_hardened_clay"));
        assertEquals(BlockCounting.Category.WOOD, BlockCounting.category("minecraft:planks"));
        assertEquals(BlockCounting.Category.STONE, BlockCounting.category("minecraft:end_stone"));
        assertEquals(BlockCounting.Category.GLASS, BlockCounting.category("minecraft:stained_glass"));
        assertEquals(BlockCounting.Category.OTHER, BlockCounting.category("minecraft:tnt"));
        assertTrue(BlockCounting.counted(BlockCounting.Category.OTHER, false, false, false, false, false, false, false));
        assertFalse(BlockCounting.counted(BlockCounting.Category.WOOD, true, true, true, false, true, true, true));
        assertTrue(BlockCounting.counted(BlockCounting.Category.WOOL, true, true, false, false, false, false, false));
    }

    // ---- BridgeGeometry

    @Test
    public void edgeDistanceForEachSideFace() {
        // Standing at x=10.5, z=20.2 on block (10, 20).
        assertEquals(0.2, BridgeGeometry.edgeDistance(10.5, 20.2, 10, 20, 2), 1e-9); // north edge z=20
        assertEquals(0.8, BridgeGeometry.edgeDistance(10.5, 20.2, 10, 20, 3), 1e-9); // south edge z=21
        assertEquals(0.5, BridgeGeometry.edgeDistance(10.5, 20.2, 10, 20, 4), 1e-9);
        assertEquals(0.5, BridgeGeometry.edgeDistance(10.5, 20.2, 10, 20, 5), 1e-9);
        assertTrue(Double.isNaN(BridgeGeometry.edgeDistance(10.5, 20.2, 10, 20, 1)));
        assertEquals(1f, BridgeGeometry.closeness(0.2), 1e-6f);
        assertEquals(1f, BridgeGeometry.closeness(-0.1), 1e-6f);
        assertEquals(0f, BridgeGeometry.closeness(1.0), 1e-6f);
        assertEquals(0f, BridgeGeometry.closeness(Double.NaN), 1e-6f);
    }
}
