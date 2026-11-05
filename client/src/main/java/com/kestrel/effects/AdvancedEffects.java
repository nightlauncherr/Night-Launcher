package com.kestrel.effects;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.EnumParticleTypes;

/**
 * Advanced kill effects, part one: lightning, energy, black hole, dragon,
 * crystals, fire tornado, frost, portal, meteor and spirit. Each is built from
 * real geometry ({@link Fx}) with vanilla particles only as accents (so the
 * particle setting and budget still apply). Layouts are randomised once in the
 * constructor into fixed arrays; drawing allocates nothing.
 */
final class AdvancedEffects {
    private AdvancedEffects() {
    }

    static ActiveEffect create(KillEffectType type, double x, double y, double z, float i, int b, int color) {
        switch (type) {
            case BOLT_STRIKE:
                return new BoltStrike(x, y, z, i, b);
            case ENERGY_NOVA:
                return new EnergyNova(x, y, z, i, b, color);
            case BLACK_HOLE:
                return new BlackHole(x, y, z, i, b);
            case DRAGON_BURST:
                return new DragonBurst(x, y, z, i, b);
            case CRYSTAL_BURST:
                return new CrystalBurst(x, y, z, i, b);
            case FIRE_TORNADO:
                return new FireTornado(x, y, z, i, b);
            case FROST_SPIKES:
                return new FrostSpikes(x, y, z, i, b);
            case PORTAL_RIFT:
                return new PortalRift(x, y, z, i, b);
            case METEOR:
                return new Meteor(x, y, z, i, b);
            case SPIRIT_ASCEND:
                return new SpiritAscend(x, y, z, i, b);
            default:
                return AdvancedEffects2.create(type, x, y, z, i, b, color);
        }
    }

    // ------------------------------------------------------------ lightning

    /** Three forked strikes from the sky, a ground flash, shock ring and scorch mark. */
    static final class BoltStrike extends Fx3dEffect {
        private static final int MAIN = 18;
        private static final int BR = 7;
        private static final int BRANCHES = 3;
        private final float[] main = new float[MAIN * 3];
        private final float[][] branch = new float[BRANCHES][BR * 3];
        private final float alpha;
        private int lastStrike;

        BoltStrike(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 28);
            alpha = vis(1f, i);
            regen();
        }

        private void regen() {
            float px = 0f;
            float pz = 0f;
            for (int k = 0; k < MAIN; k++) {
                float h = 1f - k / (float) (MAIN - 1);
                px = px * 0.7f + (float) rng.nextGaussian() * 0.75f * (0.25f + h);
                pz = pz * 0.7f + (float) rng.nextGaussian() * 0.75f * (0.25f + h);
                boolean last = k == MAIN - 1;
                main[k * 3] = last ? 0f : px;
                main[k * 3 + 1] = h * 22f;
                main[k * 3 + 2] = last ? 0f : pz;
            }
            for (int br = 0; br < BRANCHES; br++) {
                int s = 3 + rng.nextInt(MAIN - 8);
                double dir = rng.nextDouble() * Math.PI * 2;
                float bx = main[s * 3];
                float by = main[s * 3 + 1];
                float bz = main[s * 3 + 2];
                float[] p = branch[br];
                for (int j = 0; j < BR; j++) {
                    p[j * 3] = bx;
                    p[j * 3 + 1] = by;
                    p[j * 3 + 2] = bz;
                    bx += (float) (Math.cos(dir) * 0.55 + rng.nextGaussian() * 0.3);
                    by -= 0.75f + rng.nextFloat() * 0.5f;
                    bz += (float) (Math.sin(dir) * 0.55 + rng.nextGaussian() * 0.3);
                }
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 5 || tick == 10) regen();
            if (tick == 0 || tick == 5 || tick == 10) {
                lastStrike = tick;
                burst(w, EnumParticleTypes.FIREWORKS_SPARK, 14, 0.3, 0.3, 0.25);
            }
            if (tick == 0) burst(w, EnumParticleTypes.CRIT_MAGIC, 16, 0.6, 0.4, 0.4);
        }

        @Override
        void draw(float t, float u) {
            float since = Math.max(0f, t - lastStrike);
            float e = (float) Math.exp(-since * 0.45) * (1f - smooth(span(t, 14f, 28f))) * alpha;
            // Sky flash at the top of the bolt, then the bolt itself (glow + white core).
            Fx.glow(main[0], main[1], main[2], 5f, 0xBFD4FF, 0.35f * e);
            Fx.ribbon(main, MAIN, 1.6f, 1.1f, 0x5C8CFF, 0x8FB4FF, 0.45f * e, 0.5f * e);
            Fx.ribbon(main, MAIN, 0.24f, 0.16f, 0xFFFFFF, 0xDDEBFF, e, e);
            for (int br = 0; br < BRANCHES; br++) {
                Fx.ribbon(branch[br], BR, 0.7f, 0.1f, 0x6E9BFF, 0x6E9BFF, 0.35f * e, 0f);
                Fx.ribbon(branch[br], BR, 0.1f, 0.03f, 0xFFFFFF, 0xCFE0FF, e, 0f);
            }
            Fx.glow(0, 0.3, 0, 3.2f, 0x9CC0FF, 0.6f * e);
            Fx.glow(0, 0.3, 0, 1.0f, 0xFFFFFF, 0.9f * e);

            float rr = span(t, 0f, 14f);
            Fx.push(0, 0.05, 0);
            Fx.ring(0.4f + easeOut(rr) * 5f, 0.5f, 0x8FB4FF, (1f - rr) * 0.7f * alpha);
            Fx.normal();
            Fx.disc(1.6f, 0x0A0A14, 0.55f * alpha * smooth(span(t, 0f, 2f)) * (1f - span(t, 16f, 28f)), 0f);
            Fx.additive();
            Fx.pop();
        }
    }

    // ------------------------------------------------------------- energy nova

    /** A charging orb in your colour that detonates into a Fresnel shell with crossing shock rings. */
    static final class EnergyNova extends Fx3dEffect {
        private final int color;
        private final int light;
        private final float alpha;

        EnergyNova(double x, double y, double z, float i, int b, int color) {
            super(x, y, z, i, b, 30);
            this.color = color & 0xFFFFFF;
            this.light = Fx.lerpColor(this.color, 0xFFFFFF, 0.55f);
            alpha = vis(1f, i);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 7) burst(w, EnumParticleTypes.FIREWORKS_SPARK, 30, 1.1, 0.2, 0.45);
        }

        @Override
        void draw(float t, float u) {
            float cy = 1.1f;
            if (t < 7f) {
                float charge = span(t, 0f, 7f);
                float r = 0.25f + 0.45f * easeOut(charge) + 0.05f * (float) Math.sin(t * 2.2f);
                Fx.glow(0, cy, 0, r * 3.2f, color, 0.55f * alpha * charge);
                Fx.sphere(0, cy, 0, r, light, 0.85f * alpha, 1f);
                Fx.glow(0, cy, 0, r * 1.1f, 0xFFFFFF, 0.8f * alpha);
                Fx.push(0, cy, 0);
                for (int k = 0; k < 3; k++) {
                    Fx.push(0, 0, 0);
                    Fx.rot(t * 24f + k * 60f, 0f, 1f, 0f);
                    Fx.rot(55f + k * 35f, 1f, 0f, 0f);
                    Fx.ring(r * 1.9f, 0.07f, light, 0.7f * alpha * charge);
                    Fx.pop();
                }
                Fx.pop();
                return;
            }
            float boom = span(t, 7f, 26f);
            float e = easeOut(boom);
            float big = 0.6f + e * 3.4f;
            float fade = 1f - boom;
            Fx.sphere(0, cy, 0, big, color, 0.75f * alpha * fade, 1f);
            Fx.sphere(0, cy, 0, big * 0.55f, light, 0.35f * alpha * fade * fade, 1f);
            Fx.glow(0, cy, 0, 2.2f + e * 2f, light, 0.9f * alpha * (1f - span(t, 7f, 15f)));
            Fx.push(0, cy, 0);
            Fx.ring(big * 1.15f, 0.35f, light, 0.8f * alpha * fade);
            Fx.rot(70f, 1f, 0f, 0f);
            Fx.rot(t * 5f, 0f, 1f, 0f);
            Fx.ring(big * 1.0f, 0.25f, color, 0.6f * alpha * fade);
            Fx.rot(90f, 0f, 0f, 1f);
            Fx.ring(big * 0.9f, 0.18f, light, 0.5f * alpha * fade);
            Fx.pop();
        }
    }

    // --------------------------------------------------------------- black hole

    /** Event horizon with a photon ring and a tilted, spinning accretion disk; collapses into a flash. */
    static final class BlackHole extends Fx3dEffect {
        private static final int STREAKS = 8;
        private static final int SP = 6;
        private final float[] buf = new float[SP * 3];
        private final float[] phase = new float[STREAKS];
        private final float alpha;

        BlackHole(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 52);
            alpha = vis(1f, i);
            for (int k = 0; k < STREAKS; k++) phase[k] = rng.nextFloat() * 6.2832f;
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < 38 && tick % 3 == 0) burst(w, EnumParticleTypes.PORTAL, 4, 1.6, 1.4, 0.5);
            if (tick == 43) burst(w, EnumParticleTypes.SPELL_WITCH, 24, 1.6, 0.4, 0.3);
        }

        @Override
        void draw(float t, float u) {
            float cy = 1.6f;
            float s = easeOutBack(span(t, 0f, 8f)) * (1f - easeIn(span(t, 38f, 44f)));
            if (s > 0.001f) {
                Fx.glow(0, cy, 0, 3.4f * s, 0x6A2CFF, 0.35f * alpha);
                Fx.push(0, cy, 0);
                Fx.rot(18f, 1f, 0f, 0f);
                Fx.rot(12f, 0f, 0f, 1f);
                Fx.scale(s, s, s);
                Fx.ring(1.5f, 2.2f, 0xB04CFF, 0.22f * alpha);
                for (int k = 0; k < 5; k++) {
                    float rr = 0.82f + k * 0.38f;
                    int col = Fx.lerpColor(0xFFF0C8, 0x7A2CFF, k / 4f);
                    Fx.push(0, 0, 0);
                    Fx.rot(t * (30f - k * 4.5f), 0f, 1f, 0f);
                    for (int j = 0; j < 3; j++) {
                        float from = j * 2.0944f;
                        Fx.arcBand(rr - 0.12f, rr + 0.12f, from, from + 2.0944f * 0.72f, col, (0.85f - k * 0.13f) * 0.6f * alpha);
                    }
                    Fx.pop();
                }
                Fx.rot(t * 36f, 0f, 1f, 0f);
                for (int arm = 0; arm < 4; arm++) {
                    Fx.spiralBand(2.6f, 0.7f, 0.9f, arm * 1.5708f, 0.22f, 0x8A3CFF, 0xFFE2B0, 0f, 0.7f * alpha);
                }
                Fx.pop();

                // In-falling streaks (analytic spiral paths, no history kept).
                for (int k = 0; k < STREAKS; k++) {
                    float lt = (t + k * 1.75f) % 14f;
                    for (int p = 0; p < SP; p++) {
                        float tt = Math.max(0f, lt - p * 0.45f);
                        float f = tt / 14f;
                        float rr = (0.65f + 2.3f * (float) Math.pow(1f - f, 1.5)) * s;
                        float ang = phase[k] + tt * 1.1f / (0.4f + rr);
                        buf[p * 3] = (float) Math.cos(ang) * rr;
                        buf[p * 3 + 1] = cy + (float) Math.sin(ang) * rr * 0.31f;
                        buf[p * 3 + 2] = (float) Math.sin(ang) * rr;
                    }
                    Fx.ribbon(buf, SP, 0.12f * s, 0.02f, 0xFFE8C0, 0xA060FF, 0.8f * alpha, 0f);
                }

                // The horizon is drawn dark (ordinary blending) over the disk behind it.
                Fx.normal();
                Fx.sphere(0, cy, 0, 0.62f * s, 0x000000, 0.97f, 0f);
                Fx.additive();
                Fx.billboardRing(0, cy, 0, 0.68f * s, 0.14f, 0xFFD9A0, 0.9f * alpha);
                Fx.billboardRing(0, cy, 0, 0.8f * s, 0.3f, 0x9A5CFF, 0.5f * alpha);
            }
            if (t > 43f) {
                float f = 1f - span(t, 43f, 52f);
                Fx.glow(0, cy, 0, 5f * easeOut(span(t, 43f, 48f)), 0xE8D8FF, 0.9f * alpha * f);
                Fx.push(0, cy, 0);
                Fx.ring(0.5f + 6f * easeOut(span(t, 43f, 52f)), 0.6f, 0xB080FF, 0.8f * alpha * f);
                Fx.pop();
            }
        }
    }

    // ------------------------------------------------------------ dragon burst

    /** Three serpentine dragons coil up around the victim and burst into violet fire at the top. */
    static final class DragonBurst extends Fx3dEffect {
        private static final int BODY = 22;
        private final float[] buf = new float[BODY * 3];
        private final float alpha;

        DragonBurst(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 44);
            alpha = vis(1f, i);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < 20 && tick % 2 == 0) burst(w, EnumParticleTypes.SPELL_WITCH, 2, 0.4, 0.8, 0.05);
            if (tick == 20) {
                int n = allowance((int) (40 * intensity));
                for (int k = 0; k < n; k++) {
                    w.spawnParticle(k % 2 == 0 ? EnumParticleTypes.SPELL_WITCH : EnumParticleTypes.PORTAL, x, y + 4.8, z,
                            rng.nextGaussian() * 0.4, rng.nextGaussian() * 0.3, rng.nextGaussian() * 0.4);
                }
            }
        }

        @Override
        void draw(float t, float u) {
            float hp = easeOut(span(t, 0f, 20f));
            float fb = 1f - span(t, 22f, 30f);
            float env = smooth(span(t, 0f, 4f)) * (1f - span(t, 34f, 44f));
            Fx.push(0, 0.05, 0);
            Fx.rot(t * 4f, 0f, 1f, 0f);
            Fx.ring(1.4f, 0.14f, 0xA040FF, 0.6f * alpha * env);
            Fx.polygonBand(6, 1.25f, 0.06f, 0f, 0xD080FF, 0.55f * alpha * env);
            Fx.polygonBand(3, 1.1f, 0.05f, 0f, 0xD080FF, 0.4f * alpha * env);
            Fx.polygonBand(3, 1.1f, 0.05f, (float) Math.PI, 0xD080FF, 0.4f * alpha * env);
            Fx.pop();
            if (fb > 0f) {
                for (int k = 0; k < 3; k++) {
                    float phase = k * 2.0944f;
                    for (int j = 0; j < BODY; j++) {
                        float q = Math.max(0f, hp - j * 0.032f);
                        float ang = phase + q * 9.4f + (float) Math.sin(t * 0.3f + k) * 0.1f;
                        float rad = 1.15f * (1f - 0.45f * q) + 0.12f * (float) Math.sin(q * 12f + k);
                        buf[j * 3] = (float) Math.cos(ang) * rad;
                        buf[j * 3 + 1] = 0.2f + q * 4.6f;
                        buf[j * 3 + 2] = (float) Math.sin(ang) * rad;
                    }
                    Fx.ribbon(buf, BODY, 0.6f, 0.05f, 0xFF5CF4, 0x4A0F8C, 0.9f * alpha * fb, 0f);
                    Fx.ribbon(buf, BODY, 0.16f, 0.02f, 0xFFFFFF, 0xC070FF, 0.9f * alpha * fb, 0f);
                    Fx.glow(buf[0], buf[1], buf[2], 0.8f, 0xFF9CFF, 0.8f * alpha * fb);
                }
            }
            if (t >= 20f) {
                float bt = span(t, 20f, 38f);
                float top = 4.8f;
                Fx.sphere(0, top, 0, 0.4f + easeOut(bt) * 2.6f, 0xB044FF, 0.7f * alpha * (1f - bt), 1f);
                Fx.glow(0, top, 0, 1.5f + 3f * bt, 0xFFB0FF, 0.9f * alpha * (1f - span(t, 20f, 28f)));
                Fx.push(0, top, 0);
                Fx.ring(easeOut(bt) * 3.2f, 0.4f, 0xD070FF, 0.7f * alpha * (1f - bt));
                Fx.pop();
            }
        }
    }

    // ------------------------------------------------------------ crystal burst

    /** Faceted amethyst crystals erupt from the ground, glow, then shatter into tumbling shards. */
    static final class CrystalBurst extends Fx3dEffect {
        private static final int N = 10;
        private static final int PER = 3;
        private static final int[] PALETTE = {0xB57BFF, 0x7FE3FF, 0xFF8AD8, 0xC9A6FF};
        private static final float SHATTER = 27f;
        private final float[] ang = new float[N];
        private final float[] tilt = new float[N];
        private final float[] len = new float[N];
        private final float[] rad = new float[N];
        private final float[] delay = new float[N];
        private final int[] col = new int[N];
        private final float[] frag = new float[N * PER * 10];
        private final float alpha;

        CrystalBurst(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 44);
            alpha = vis(1f, i);
            for (int k = 0; k < N; k++) {
                ang[k] = (float) (k * 2 * Math.PI / N + rng.nextGaussian() * 0.15);
                tilt[k] = 0.25f + rng.nextFloat() * 0.45f;
                len[k] = 1.2f + rng.nextFloat() * 1.2f;
                rad[k] = 0.16f + rng.nextFloat() * 0.12f;
                delay[k] = rng.nextFloat() * 4f;
                col[k] = PALETTE[k % PALETTE.length];
                float dx = (float) (Math.sin(tilt[k]) * Math.cos(ang[k]));
                float dy = (float) Math.cos(tilt[k]);
                float dz = (float) (Math.sin(tilt[k]) * Math.sin(ang[k]));
                for (int f = 0; f < PER; f++) {
                    int o = (k * PER + f) * 10;
                    float along = len[k] * (0.3f + 0.3f * f);
                    frag[o] = (float) Math.cos(ang[k]) * 0.3f + dx * along;
                    frag[o + 1] = dy * along;
                    frag[o + 2] = (float) Math.sin(ang[k]) * 0.3f + dz * along;
                    frag[o + 3] = dx * 0.08f + (float) (Math.cos(ang[k]) * 0.06 + rng.nextGaussian() * 0.03);
                    frag[o + 4] = 0.12f + rng.nextFloat() * 0.08f;
                    frag[o + 5] = dz * 0.08f + (float) (Math.sin(ang[k]) * 0.06 + rng.nextGaussian() * 0.03);
                    frag[o + 6] = rng.nextFloat() - 0.5f;
                    frag[o + 7] = rng.nextFloat() - 0.5f;
                    frag[o + 8] = 8f + rng.nextFloat() * 14f;
                    frag[o + 9] = 0.06f + rng.nextFloat() * 0.07f;
                }
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 0) burst(w, EnumParticleTypes.SPELL_WITCH, 12, 0.3, 0.5, 0.1);
            if (tick == (int) SHATTER) burst(w, EnumParticleTypes.FIREWORKS_SPARK, 24, 1.0, 0.6, 0.25);
        }

        @Override
        void draw(float t, float u) {
            Fx.glow(0, 0.4, 0, 2.4f, 0xC9A6FF, 0.5f * alpha * span(t, 0f, 4f) * (1f - span(t, 24f, SHATTER)));
            if (t < SHATTER + 0.5f) {
                for (int k = 0; k < N; k++) {
                    float g = easeOutBack(span(t, delay[k], delay[k] + 5f));
                    if (g <= 0.001f) continue;
                    g *= 1f + 0.04f * (float) Math.sin(t * 0.6f + k);
                    Fx.push(Math.cos(ang[k]) * 0.3, 0, Math.sin(ang[k]) * 0.3);
                    Fx.rot(-deg(ang[k]), 0f, 1f, 0f);
                    Fx.rot(-deg(tilt[k]), 0f, 0f, 1f);
                    Fx.normal();
                    Fx.crystal(rad[k] * g, 0.3f * len[k] * g, len[k] * g, 6, col[k], 0.78f * alpha);
                    Fx.additive();
                    Fx.crystalEdges(rad[k] * g, 0.3f * len[k] * g, len[k] * g, 6, Fx.lerpColor(col[k], 0xFFFFFF, 0.6f), 0.9f * alpha, 2f);
                    Fx.pop();
                }
            }
            Fx.glow(0, 0.9, 0, 3.5f, 0xFFFFFF, 0.8f * alpha * span(t, SHATTER - 1f, SHATTER + 0.5f) * (1f - span(t, SHATTER, SHATTER + 5f)));
            if (t >= SHATTER) {
                float dt = t - SHATTER;
                float fade = (1f - span(t, 34f, 44f)) * alpha;
                for (int k = 0; k < N; k++) {
                    for (int f = 0; f < PER; f++) {
                        int o = (k * PER + f) * 10;
                        float py = Math.max(0.05f, frag[o + 1] + frag[o + 4] * dt - 0.02f * dt * dt);
                        float sz = frag[o + 9];
                        Fx.push(frag[o] + frag[o + 3] * dt, py, frag[o + 2] + frag[o + 5] * dt);
                        Fx.rot(dt * frag[o + 8], frag[o + 6], 1f, frag[o + 7]);
                        Fx.normal();
                        Fx.crystal(sz, sz * 0.6f, sz * 1.5f, 4, col[k], 0.85f * fade);
                        Fx.additive();
                        Fx.crystalEdges(sz, sz * 0.6f, sz * 1.5f, 4, 0xFFFFFF, 0.6f * fade, 1.5f);
                        Fx.pop();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- fire tornado

    /** A spinning funnel of flame ribbons around a burning core, which lifts off and burns out. */
    static final class FireTornado extends Fx3dEffect {
        private static final int STRANDS = 6;
        private static final int PTS = 26;
        private final float[] buf = new float[PTS * 3];
        private final float alpha;

        FireTornado(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 48);
            alpha = vis(1f, i);
        }

        private float height(float t) {
            return 5.6f * easeOut(span(t, 0f, 10f));
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick >= 40) return;
            float hgt = height(tick);
            int n = allowance(Math.max(1, (int) (3 * intensity)));
            for (int k = 0; k < n; k++) {
                float h = rng.nextFloat() * hgt;
                float r = 0.22f + h * 0.3f;
                double a = rng.nextDouble() * Math.PI * 2;
                w.spawnParticle(EnumParticleTypes.FLAME, x + Math.cos(a) * r, y + h, z + Math.sin(a) * r,
                        -Math.sin(a) * 0.08, 0.03, Math.cos(a) * 0.08);
            }
            if (tick % 4 == 0 && allowance(1) == 1) {
                w.spawnParticle(EnumParticleTypes.LAVA, x, y + 0.3, z, 0, 0, 0);
            }
        }

        @Override
        void draw(float t, float u) {
            float hgt = height(t);
            float lift = easeIn(span(t, 34f, 48f)) * 3f;
            float fade = (1f - span(t, 36f, 48f)) * alpha;
            for (int s = 0; s < STRANDS; s++) {
                for (int k = 0; k < PTS; k++) {
                    float h = k / (float) (PTS - 1) * hgt;
                    float r = 0.22f + h * 0.3f + 0.07f * (float) Math.sin(t * 0.45f + h * 2.6f + s);
                    float a = s * 1.0472f + t * 0.5f + h * 1.4f;
                    buf[k * 3] = (float) Math.cos(a) * r;
                    buf[k * 3 + 1] = h + lift;
                    buf[k * 3 + 2] = (float) Math.sin(a) * r;
                }
                Fx.ribbon(buf, PTS, 0.34f, 0.7f, 0xFFC040, 0xD02808, 0.8f * fade, 0.1f * fade);
            }
            Fx.push(0, lift, 0);
            Fx.beam(0.15f, 1.6f * hgt / 5.6f, hgt, 0xFF5A0A, 0.25f * fade, 0f);
            Fx.pop();
            Fx.push(0, 0.04, 0);
            Fx.disc(1.8f, 0xFF6A10, 0.45f * fade * (1f - span(t, 30f, 40f)), 0f);
            Fx.ring(1.2f + 0.1f * (float) Math.sin(t), 0.5f, 0xFFB040, 0.5f * fade);
            Fx.pop();
            Fx.glow(0, 0.6 + lift, 0, 2.2f, 0xFF8A2A, 0.45f * fade);
        }
    }

    // -------------------------------------------------------------- frost spikes

    /** A ring of ice spikes bursts from a frosted floor, then shatters into falling ice. */
    static final class FrostSpikes extends Fx3dEffect {
        private static final int N = 10;
        private static final int PER = 4;
        private static final float SHATTER = 24f;
        private final float[] bx = new float[N];
        private final float[] bz = new float[N];
        private final float[] ang = new float[N];
        private final float[] tilt = new float[N];
        private final float[] len = new float[N];
        private final float[] rad = new float[N];
        private final float[] delay = new float[N];
        private final float[] frag = new float[N * PER * 10];
        private final float alpha;

        FrostSpikes(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 46);
            alpha = vis(1f, i);
            for (int k = 0; k < N; k++) {
                boolean centre = k == 0;
                ang[k] = (float) ((k - 1) * 2 * Math.PI / (N - 1) + rng.nextGaussian() * 0.12);
                float r = centre ? 0f : 0.9f + rng.nextFloat() * 0.6f;
                bx[k] = (float) Math.cos(ang[k]) * r;
                bz[k] = (float) Math.sin(ang[k]) * r;
                tilt[k] = centre ? 0f : 0.2f + rng.nextFloat() * 0.35f;
                len[k] = centre ? 2.6f : 1.1f + rng.nextFloat() * 0.9f;
                rad[k] = centre ? 0.24f : 0.12f + rng.nextFloat() * 0.08f;
                delay[k] = centre ? 0f : 1f + (k - 1) * 0.5f;
                float dx = (float) (Math.sin(tilt[k]) * Math.cos(ang[k]));
                float dy = (float) Math.cos(tilt[k]);
                float dz = (float) (Math.sin(tilt[k]) * Math.sin(ang[k]));
                for (int f = 0; f < PER; f++) {
                    int o = (k * PER + f) * 10;
                    float along = len[k] * (0.2f + 0.22f * f);
                    frag[o] = bx[k] + dx * along;
                    frag[o + 1] = dy * along;
                    frag[o + 2] = bz[k] + dz * along;
                    frag[o + 3] = (float) (dx * 0.05 + rng.nextGaussian() * 0.04);
                    frag[o + 4] = 0.04f + rng.nextFloat() * 0.08f;
                    frag[o + 5] = (float) (dz * 0.05 + rng.nextGaussian() * 0.04);
                    frag[o + 6] = rng.nextFloat() - 0.5f;
                    frag[o + 7] = rng.nextFloat() - 0.5f;
                    frag[o + 8] = 10f + rng.nextFloat() * 16f;
                    frag[o + 9] = 0.05f + rng.nextFloat() * 0.06f;
                }
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 0) burst(w, EnumParticleTypes.SNOW_SHOVEL, 20, 0.3, 1.0, 0.1);
            if (tick == (int) SHATTER) {
                burst(w, EnumParticleTypes.SNOWBALL, 20, 1.0, 0.8, 0.2);
                burst(w, EnumParticleTypes.FIREWORKS_SPARK, 10, 1.0, 0.5, 0.2);
            }
        }

        @Override
        void draw(float t, float u) {
            float spread = easeOut(span(t, 0f, 6f));
            float discFade = 1f - span(t, 32f, 46f);
            Fx.push(0, 0.03, 0);
            Fx.normal();
            Fx.disc(2.4f * spread, 0xE8F8FF, 0.5f * alpha * discFade, 0.05f * alpha * discFade);
            Fx.additive();
            Fx.ring(2.2f * spread, 0.25f, 0xA8E8FF, 0.5f * alpha * discFade);
            for (int k = 1; k < N; k++) {
                Fx.stroke(0f, 0f, bx[k] * 1.6f * spread, bz[k] * 1.6f * spread, 0.05f, 0xCFF4FF, 0.6f * alpha * discFade);
            }
            Fx.pop();
            Fx.glow(0, 0.5, 0, 2.6f, 0x9FE4FF, 0.3f * alpha * discFade);
            if (t < SHATTER + 0.5f) {
                for (int k = 0; k < N; k++) {
                    float g = easeOutBack(span(t, delay[k], delay[k] + 4f));
                    if (g <= 0.001f) continue;
                    Fx.push(bx[k], 0, bz[k]);
                    Fx.rot(-deg(ang[k]), 0f, 1f, 0f);
                    Fx.rot(-deg(tilt[k]), 0f, 0f, 1f);
                    Fx.rot(45f, 0f, 1f, 0f);
                    Fx.normal();
                    Fx.crystal(rad[k] * g, 0.15f * g, len[k] * g, 4, 0xCFF4FF, 0.72f * alpha);
                    Fx.additive();
                    Fx.crystalEdges(rad[k] * g, 0.15f * g, len[k] * g, 4, 0xFFFFFF, 0.85f * alpha, 2f);
                    Fx.beam(rad[k] * 0.3f * g, 0f, len[k] * g * 0.9f, 0x7FD8FF, 0.35f * alpha, 0f);
                    Fx.pop();
                }
            }
            if (t >= SHATTER) {
                float dt = t - SHATTER;
                float fade = (1f - span(t, 36f, 46f)) * alpha;
                for (int k = 0; k < N * PER; k++) {
                    int o = k * 10;
                    float py = Math.max(0.05f, frag[o + 1] + frag[o + 4] * dt - 0.03f * dt * dt);
                    float sz = frag[o + 9];
                    Fx.push(frag[o] + frag[o + 3] * dt, py, frag[o + 2] + frag[o + 5] * dt);
                    Fx.rot(py > 0.05f ? dt * frag[o + 8] : 0f, frag[o + 6], 1f, frag[o + 7]);
                    Fx.normal();
                    Fx.cube(sz, 0xDDF6FF, 0.8f * fade);
                    Fx.pop();
                }
            }
        }
    }

    // -------------------------------------------------------------- portal rift

    /** A swirling oval rift opens facing you, its rings spinning in opposite directions, then snaps shut. */
    static final class PortalRift extends Fx3dEffect {
        private final float alpha;
        private final float hx;
        private final float hz;

        PortalRift(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 52);
            alpha = vis(1f, i);
            double yr = Math.toRadians(viewYaw);
            hx = (float) Math.cos(yr);
            hz = (float) Math.sin(yr);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 0) burst(w, EnumParticleTypes.SPELL_WITCH, 10, 1.6, 0.4, 0.1);
            if (tick < 42 && tick % 2 == 0) {
                float open = easeOut(span(tick, 0f, 9f));
                int n = allowance(Math.max(1, (int) (3 * intensity)));
                for (int k = 0; k < n; k++) {
                    double th = rng.nextDouble() * Math.PI * 2;
                    double px = Math.cos(th) * 1.3 * (0.4 + 0.6 * open);
                    double py = Math.sin(th) * 1.3 * 1.55 * open;
                    w.spawnParticle(EnumParticleTypes.PORTAL, x + hx * px, y + 1.6 + py, z + hz * px,
                            -hx * px * 0.6, -py * 0.6, -hz * px * 0.6);
                }
            }
            if (tick == 50) burst(w, EnumParticleTypes.PORTAL, 30, 1.6, 0.3, 0.6);
        }

        @Override
        void draw(float t, float u) {
            float open = easeOut(span(t, 0f, 9f));
            float close = easeIn(span(t, 42f, 52f));
            float sy = open * (1f - close);
            float sx = (0.4f + 0.6f * open) * (1f - close * 0.3f);
            if (sy > 0.002f) {
                Fx.push(0, 1.6, 0);
                Fx.rot(-viewYaw, 0f, 1f, 0f);
                Fx.rot(90f, 1f, 0f, 0f);
                Fx.scale(sx, 1f, 1.55f * sy);
                Fx.normal();
                Fx.disc(1.15f, 0x12021F, 0.85f * alpha, 0.5f * alpha);
                Fx.additive();
                Fx.ring(1.25f, 0.5f, 0x9A50FF, 0.6f * alpha);
                Fx.ring(1.32f, 0.12f, 0xF0E0FF, 0.9f * alpha);
                for (int k = 0; k < 4; k++) {
                    float rr = 1.2f - k * 0.15f;
                    Fx.push(0, 0, 0);
                    Fx.rot((k % 2 == 0 ? 1f : -1f) * t * (6f + k * 3f), 0f, 1f, 0f);
                    for (int j = 0; j < 4; j++) {
                        float from = j * 1.5708f;
                        Fx.arcBand(rr - 0.03f, rr + 0.03f, from, from + 1.5708f * 0.7f,
                                k % 2 == 0 ? 0xB060FF : 0x5FE0FF, 0.85f * alpha);
                    }
                    Fx.pop();
                }
                Fx.rot(t * 9f, 0f, 1f, 0f);
                for (int arm = 0; arm < 5; arm++) {
                    Fx.spiralBand(1.1f, 0.05f, 1.3f, arm * 1.2566f, 0.18f, 0x6E2BFF, 0xE6C8FF, 0f, 0.8f * alpha);
                }
                Fx.pop();
                Fx.glow(0, 1.6, 0, 2.6f * sy, 0x8040FF, 0.35f * alpha);
            }
            if (t > 46f) {
                Fx.glow(0, 1.6, 0, 2.5f, 0xF4ECFF, 0.9f * alpha * span(t, 46f, 50f) * (1f - span(t, 50f, 52f)));
            }
        }
    }

    // -------------------------------------------------------------------- meteor

    /** A burning meteor streaks down from the sky and slams into the ground: flash, dome, rings, debris. */
    static final class Meteor extends Fx3dEffect {
        private static final float IMPACT = 12f;
        private static final int TAIL = 14;
        private static final int DEBRIS = 16;
        private static final int CRACKS = 7;
        private final float sx;
        private final float sy;
        private final float sz;
        private final float[] buf = new float[TAIL * 3];
        private final float[] deb = new float[DEBRIS * 7];
        private final float[] crack = new float[CRACKS * 2];
        private final float alpha;

        Meteor(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 46);
            alpha = vis(1f, i);
            double sa = rng.nextDouble() * Math.PI * 2;
            sx = (float) Math.cos(sa) * 7f;
            sy = 18f;
            sz = (float) Math.sin(sa) * 7f;
            for (int k = 0; k < DEBRIS; k++) {
                double a = rng.nextDouble() * Math.PI * 2;
                double sp = 0.08 + rng.nextDouble() * 0.12;
                deb[k * 7] = (float) (Math.cos(a) * sp);
                deb[k * 7 + 1] = 0.2f + rng.nextFloat() * 0.2f;
                deb[k * 7 + 2] = (float) (Math.sin(a) * sp);
                deb[k * 7 + 3] = rng.nextFloat() - 0.5f;
                deb[k * 7 + 4] = rng.nextFloat() - 0.5f;
                deb[k * 7 + 5] = 10f + rng.nextFloat() * 20f;
                deb[k * 7 + 6] = 0.06f + rng.nextFloat() * 0.07f;
            }
            for (int k = 0; k < CRACKS; k++) {
                crack[k * 2] = (float) (k * 2 * Math.PI / CRACKS + rng.nextGaussian() * 0.2);
                crack[k * 2 + 1] = 1.2f + rng.nextFloat() * 1.0f;
            }
        }

        private float fall(float t) {
            return (float) Math.pow(clamp01(t / IMPACT), 1.7);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < IMPACT) {
                float f = fall(tick);
                if (allowance(2) > 0) {
                    w.spawnParticle(EnumParticleTypes.SMOKE_LARGE, x + sx * (1 - f), y + 0.4 + sy * (1 - f), z + sz * (1 - f), 0, 0.02, 0);
                }
            }
            if (tick == (int) IMPACT) {
                burst(w, EnumParticleTypes.EXPLOSION_LARGE, 3, 0.5, 0.5, 0.0);
                burst(w, EnumParticleTypes.LAVA, 10, 0.2, 0.4, 0.1);
                burst(w, EnumParticleTypes.SMOKE_LARGE, 16, 0.3, 0.6, 0.1);
            }
        }

        @Override
        void draw(float t, float u) {
            if (t < IMPACT) {
                float f = fall(t);
                float px = sx * (1 - f);
                float py = 0.4f + sy * (1 - f);
                float pz = sz * (1 - f);
                for (int j = 0; j < TAIL; j++) {
                    float fj = fall(Math.max(0f, t - j * 0.45f));
                    buf[j * 3] = sx * (1 - fj);
                    buf[j * 3 + 1] = 0.4f + sy * (1 - fj);
                    buf[j * 3 + 2] = sz * (1 - fj);
                }
                Fx.ribbon(buf, TAIL, 2.4f, 0.6f, 0xFF7A20, 0x401008, 0.35f * alpha, 0f);
                Fx.ribbon(buf, TAIL, 1.2f, 0.1f, 0xFFF4C0, 0xB02000, 0.95f * alpha, 0f);
                Fx.glow(px, py, pz, 2.2f, 0xFF9A30, 0.8f * alpha);
                Fx.normal();
                Fx.sphere(px, py, pz, 0.42f, 0x3A1608, 0.95f, 0f);
                Fx.additive();
                Fx.sphere(px, py, pz, 0.5f, 0xFFB040, 0.9f * alpha, 1f);
                Fx.glow(px, py, pz, 0.6f, 0xFFFFFF, 0.7f * alpha);
                return;
            }
            float it = t - IMPACT;
            Fx.glow(0, 0.6, 0, 5.5f * easeOut(span(it, 0f, 3f)), 0xFFE0A0, 0.9f * alpha * (1f - span(it, 0f, 8f)));
            Fx.sphere(0, 0, 0, 0.5f + easeOut(span(it, 0f, 10f)) * 3.2f, 0xFF7A20, 0.6f * alpha * (1f - span(it, 0f, 12f)), 1f);
            Fx.push(0, 0.08, 0);
            Fx.ring(0.4f + easeOut(span(it, 0f, 14f)) * 6f, 0.8f, 0xFFB050, 0.75f * alpha * (1f - span(it, 0f, 14f)));
            Fx.ring(0.3f + easeOut(span(it, 2f, 18f)) * 4f, 0.4f, 0xFF5020, 0.6f * alpha * (1f - span(it, 2f, 18f)));
            Fx.pop();
            Fx.push(0, 0.03, 0);
            Fx.normal();
            Fx.disc(2.0f, 0x140804, 0.7f * alpha * (1f - span(it, 18f, 34f)), 0f);
            Fx.additive();
            float hot = 0.8f * alpha * (1f - span(it, 6f, 30f));
            for (int k = 0; k < CRACKS; k++) {
                float a = crack[k * 2];
                float l = crack[k * 2 + 1] * easeOut(span(it, 0f, 4f));
                Fx.stroke(0f, 0f, (float) Math.cos(a) * l, (float) Math.sin(a) * l, 0.09f, 0xFF6A10, hot);
            }
            Fx.pop();
            float debFade = 1f - span(it, 26f, 34f);
            for (int k = 0; k < DEBRIS; k++) {
                float py = Math.max(0.1f, deb[k * 7 + 1] * it - 0.035f * it * it);
                boolean landed = py <= 0.1f && it > 2f;
                float tFly = landed ? deb[k * 7 + 1] / 0.035f : it;
                float sz = deb[k * 7 + 6];
                Fx.push(deb[k * 7] * tFly, py, deb[k * 7 + 2] * tFly);
                Fx.rot(tFly * deb[k * 7 + 5], deb[k * 7 + 3], 1f, deb[k * 7 + 4]);
                Fx.normal();
                Fx.cube(sz, Fx.lerpColor(0xFF7020, 0x4A2A1A, span(it, 0f, 10f)), 0.95f * debFade);
                Fx.pop();
            }
        }
    }

    // ------------------------------------------------------------ spirit ascend

    /** A soul orb rises out of the victim trailed by spiralling wisps, then crowns itself with a halo. */
    static final class SpiritAscend extends Fx3dEffect {
        private static final int WISPS = 5;
        private static final int PTS = 16;
        private final float[] buf = new float[PTS * 3];
        private final float[] phase = new float[WISPS];
        private final float alpha;

        SpiritAscend(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 54);
            alpha = vis(1f, i);
            for (int k = 0; k < WISPS; k++) phase[k] = (float) (k * 2 * Math.PI / WISPS);
        }

        private static float orbY(float t) {
            return 1.0f + smooth(span(t, 4f, 34f)) * 5.5f;
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 0) burst(w, EnumParticleTypes.SPELL_INSTANT, 10, 1.0, 0.4, 0.05);
            if (tick == 34) {
                int n = allowance((int) (16 * intensity));
                for (int k = 0; k < n; k++) {
                    w.spawnParticle(EnumParticleTypes.FIREWORKS_SPARK, x, y + orbY(34f), z,
                            rng.nextGaussian() * 0.12, rng.nextDouble() * 0.1, rng.nextGaussian() * 0.12);
                }
            }
        }

        @Override
        void draw(float t, float u) {
            float f = smooth(span(t, 0f, 4f)) * (1f - span(t, 40f, 54f)) * alpha;
            float oy = orbY(t);
            Fx.beam(0.5f, 0.2f, 6.5f, 0x9FD8FF, 0.25f * alpha * (1f - span(t, 10f, 30f)), 0f);
            for (int k = 0; k < WISPS; k++) {
                for (int j = 0; j < PTS; j++) {
                    float tt = Math.max(0f, t - j * 0.9f);
                    float r = 0.75f * (1f - smooth(span(tt, 4f, 34f))) + 0.12f;
                    float a = phase[k] + tt * 0.32f;
                    buf[j * 3] = (float) Math.cos(a) * r;
                    buf[j * 3 + 1] = orbY(tt);
                    buf[j * 3 + 2] = (float) Math.sin(a) * r;
                }
                Fx.ribbon(buf, PTS, 0.38f, 0f, 0xE6FBFF, 0x5E8CFF, 0.85f * f, 0f);
            }
            Fx.glow(0, oy, 0, 1.4f, 0xBFEFFF, 0.8f * f);
            Fx.sphere(0, oy, 0, 0.22f, 0xFFFFFF, 0.9f * f, 0.6f);
            if (t >= 30f) {
                float h = span(t, 30f, 54f);
                Fx.push(0, oy + 0.45, 0);
                Fx.ring(0.35f + easeOut(h) * 1.6f, 0.14f, 0xFFF6C8, 0.9f * alpha * (1f - h));
                Fx.ring(0.3f + easeOut(h) * 2.4f, 0.3f, 0xBFE4FF, 0.5f * alpha * (1f - h));
                Fx.pop();
            }
        }
    }
}
