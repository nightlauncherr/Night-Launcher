package com.kestrel.effects;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.EnumParticleTypes;

/**
 * Advanced kill effects, part two: plasma sphere, dark vortex, prism burst,
 * star rain, void burst, magic circle, flame wings, shattered dimension, neon
 * pulse and the Kestrel talon signature. Same rules as {@link AdvancedEffects}.
 */
final class AdvancedEffects2 {
    private AdvancedEffects2() {
    }

    static ActiveEffect create(KillEffectType type, double x, double y, double z, float i, int b, int color) {
        switch (type) {
            case PLASMA_SPHERE:
                return new PlasmaSphere(x, y, z, i, b);
            case DARK_VORTEX:
                return new DarkVortex(x, y, z, i, b);
            case PRISM_BURST:
                return new PrismBurst(x, y, z, i, b);
            case STAR_RAIN:
                return new StarRain(x, y, z, i, b);
            case VOID_BURST:
                return new VoidBurst(x, y, z, i, b);
            case MAGIC_CIRCLE:
                return new MagicCircle(x, y, z, i, b, color);
            case FLAME_WINGS:
                return new FlameWings(x, y, z, i, b);
            case SHATTERED_DIMENSION:
                return new ShatteredDimension(x, y, z, i, b);
            case NEON_PULSE:
                return new NeonPulse(x, y, z, i, b, color);
            case KESTREL_TALON:
                return new KestrelTalon(x, y, z, i, b);
            default:
                throw new IllegalArgumentException("not an advanced effect: " + type);
        }
    }

    // ----------------------------------------------------------- plasma sphere

    /** A crackling plasma shell with arcs racing over its surface and down to the ground; implodes. */
    static final class PlasmaSphere extends Fx3dEffect {
        private static final int ARCS = 7;
        private static final int AP = 9;
        private static final int BOLTS = 2;
        private static final int BP = 8;
        private static final float CY = 1.3f;
        private final float[][] arc = new float[ARCS][AP * 3];
        private final float[][] bolt = new float[BOLTS][BP * 3];
        private final float[] buf = new float[AP * 3];
        private final float alpha;

        PlasmaSphere(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 36);
            alpha = vis(1f, i);
            regen(0f);
        }

        private float radius(float t) {
            return 1.3f * easeOutBack(span(t, 0f, 6f)) * (1f - easeIn(span(t, 30f, 36f)))
                    * (1f + 0.04f * (float) Math.sin(t * 1.7f));
        }

        private void regen(float t) {
            for (int k = 0; k < ARCS; k++) {
                float ax = (float) rng.nextGaussian(), ay = (float) rng.nextGaussian(), az = (float) rng.nextGaussian();
                float bx = ax + (float) rng.nextGaussian() * 0.9f;
                float by = ay + (float) rng.nextGaussian() * 0.9f;
                float bz = az + (float) rng.nextGaussian() * 0.9f;
                for (int p = 0; p < AP; p++) {
                    float s = p / (float) (AP - 1);
                    float j = p == 0 || p == AP - 1 ? 0f : 0.09f;
                    float vx = ax + (bx - ax) * s + (float) rng.nextGaussian() * j;
                    float vy = ay + (by - ay) * s + (float) rng.nextGaussian() * j;
                    float vz = az + (bz - az) * s + (float) rng.nextGaussian() * j;
                    float l = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    if (l < 1e-4f) l = 1f;
                    arc[k][p * 3] = vx / l;
                    arc[k][p * 3 + 1] = vy / l;
                    arc[k][p * 3 + 2] = vz / l;
                }
            }
            float r = Math.max(0.3f, radius(t));
            for (int k = 0; k < BOLTS; k++) {
                double ga = rng.nextDouble() * Math.PI * 2;
                float gx = (float) Math.cos(ga) * (1.2f + rng.nextFloat());
                float gz = (float) Math.sin(ga) * (1.2f + rng.nextFloat());
                for (int p = 0; p < BP; p++) {
                    float s = p / (float) (BP - 1);
                    float j = p == 0 || p == BP - 1 ? 0f : 0.18f;
                    bolt[k][p * 3] = gx * s * 0.9f + (float) rng.nextGaussian() * j;
                    bolt[k][p * 3 + 1] = (CY - r * 0.9f) * (1f - s);
                    bolt[k][p * 3 + 2] = gz * s * 0.9f + (float) rng.nextGaussian() * j;
                }
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick % 2 == 0) regen(tick);
            if (tick == 33) burst(w, EnumParticleTypes.FIREWORKS_SPARK, 24, CY, 0.2, 0.4);
        }

        @Override
        void draw(float t, float u) {
            float r = radius(t);
            if (r > 0.01f) {
                Fx.glow(0, CY, 0, r * 2.6f, 0x3F7FFF, 0.3f * alpha);
                Fx.sphere(0, CY, 0, r, 0x3FA8FF, 0.7f * alpha, 1f);
                Fx.sphere(0, CY, 0, r * 0.97f, 0x1E50FF, 0.12f * alpha, 0f);
                Fx.glow(0, CY, 0, r * 1.2f, 0xBFE6FF, 0.6f * alpha);
                for (int k = 0; k < ARCS; k++) {
                    float flick = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 3.1f + k * 1.7f));
                    for (int p = 0; p < AP * 3; p += 3) {
                        buf[p] = arc[k][p] * r * 1.02f;
                        buf[p + 1] = CY + arc[k][p + 1] * r * 1.02f;
                        buf[p + 2] = arc[k][p + 2] * r * 1.02f;
                    }
                    Fx.ribbon(buf, AP, 0.2f, 0.2f, 0x9FD0FF, 0x9FD0FF, 0.5f * alpha * flick, 0.5f * alpha * flick);
                    Fx.ribbon(buf, AP, 0.05f, 0.05f, 0xFFFFFF, 0xFFFFFF, 0.95f * alpha * flick, 0.95f * alpha * flick);
                }
                if (t > 4f && t < 30f) {
                    for (int k = 0; k < BOLTS; k++) {
                        Fx.ribbon(bolt[k], BP, 0.35f, 0.2f, 0x7FB8FF, 0x7FB8FF, 0.4f * alpha, 0.3f * alpha);
                        Fx.ribbon(bolt[k], BP, 0.07f, 0.05f, 0xFFFFFF, 0xDDEEFF, 0.9f * alpha, 0.7f * alpha);
                    }
                }
            }
            Fx.glow(0, CY, 0, 3f, 0xFFFFFF, 0.9f * alpha * span(t, 32f, 34f) * (1f - span(t, 34f, 36f)));
        }
    }

    // ------------------------------------------------------------- dark vortex

    /** A funnel of dark smoke spirals into the victim, implodes, and releases a dark shock wave. */
    static final class DarkVortex extends Fx3dEffect {
        private static final int STRANDS = 5;
        private static final int PTS = 24;
        private static final float CY = 1.2f;
        private final float[] buf = new float[PTS * 3];
        private final float alpha;

        DarkVortex(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 46);
            alpha = vis(1f, i);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < 28) {
                int n = allowance(Math.max(1, (int) (3 * intensity)));
                for (int k = 0; k < n; k++) {
                    double a = rng.nextDouble() * Math.PI * 2;
                    double r = 1.0 + rng.nextDouble() * 1.4;
                    // SPELL_MOB takes its colour from the velocity arguments: black.
                    w.spawnParticle(EnumParticleTypes.SPELL_MOB, x + Math.cos(a) * r, y + 0.5 + rng.nextDouble() * 3.5,
                            z + Math.sin(a) * r, 0.0, 0.0, 0.0);
                }
            }
            if (tick == 34) {
                burst(w, EnumParticleTypes.SPELL_WITCH, 20, CY, 0.4, 0.4);
                burst(w, EnumParticleTypes.SMOKE_LARGE, 10, CY, 0.4, 0.15);
            }
        }

        @Override
        void draw(float t, float u) {
            float form = easeOut(span(t, 0f, 8f));
            float suck = easeIn(span(t, 26f, 34f));
            if (suck < 0.999f) {
                for (int s = 0; s < STRANDS; s++) {
                    for (int k = 0; k < PTS; k++) {
                        float q = k / (float) (PTS - 1);
                        float h = 4.6f - q * 4.2f;
                        float r = (0.2f + 2.1f * (1f - q) * (1f - q)) * form * (1f - suck);
                        float a = s * 1.2566f + t * 0.35f + q * 5f;
                        buf[k * 3] = (float) Math.cos(a) * r;
                        buf[k * 3 + 1] = CY + (h - CY) * (1f - suck) * form;
                        buf[k * 3 + 2] = (float) Math.sin(a) * r;
                    }
                    Fx.normal();
                    Fx.ribbon(buf, PTS, 0.9f, 0.25f, 0x1A0526, 0x05000A, 0.7f * alpha, 0.55f * alpha);
                    Fx.additive();
                    Fx.ribbon(buf, PTS, 0.12f, 0.04f, 0xA040FF, 0x6010C0, 0.8f * alpha, 0.45f * alpha);
                }
                Fx.push(0, CY + (4.6f - CY) * (1f - suck) * form, 0);
                Fx.ring(2.3f * form * (1f - suck), 0.3f, 0x7A30D0, 0.5f * alpha);
                Fx.pop();
                Fx.normal();
                Fx.sphere(0, CY, 0, 0.35f * form + 0.2f * suck, 0x0A0010, 0.9f, 0f);
                Fx.additive();
                Fx.billboardRing(0, CY, 0, 0.4f * form + 0.2f * suck, 0.12f, 0xC070FF, 0.7f * alpha);
            }
            if (t >= 34f) {
                float s = span(t, 34f, 46f);
                float r = 0.5f + easeOut(s) * 5f;
                Fx.glow(0, CY, 0, 3.2f, 0xC080FF, 0.8f * alpha * (1f - span(t, 34f, 40f)));
                Fx.push(0, CY, 0);
                Fx.normal();
                Fx.ring(r, 1.2f, 0x000000, 0.7f * alpha * (1f - s));
                Fx.additive();
                Fx.ring(r + 0.6f, 0.3f, 0xC060FF, 0.8f * alpha * (1f - s));
                Fx.pop();
            }
        }
    }

    // ------------------------------------------------------------- prism burst

    /** Two dozen prism shards in every colour of the rainbow shoot out, with rainbow rings and shell. */
    static final class PrismBurst extends Fx3dEffect {
        private static final int RAYS = 24;
        private static final float CY = 1.1f;
        private final float[] dir = new float[RAYS * 3];
        private final float[] yaw = new float[RAYS];
        private final float[] pitch = new float[RAYS];
        private final float[] len = new float[RAYS];
        private final float[] wid = new float[RAYS];
        private final float alpha;

        PrismBurst(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 32);
            alpha = vis(1f, i);
            double golden = Math.PI * (3 - Math.sqrt(5));
            for (int k = 0; k < RAYS; k++) {
                float dy = 1f - 1.3f * (k + 0.5f) / RAYS;
                float r = (float) Math.sqrt(Math.max(0f, 1f - dy * dy));
                double th = golden * k;
                float dx = (float) Math.cos(th) * r;
                float dz = (float) Math.sin(th) * r;
                dir[k * 3] = dx;
                dir[k * 3 + 1] = dy;
                dir[k * 3 + 2] = dz;
                yaw[k] = deg(Math.atan2(dx, dz));
                pitch[k] = deg(Math.acos(Math.max(-1f, Math.min(1f, dy))));
                len[k] = 2.2f + rng.nextFloat() * 1.2f;
                wid[k] = 0.07f + rng.nextFloat() * 0.05f;
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick != 0) return;
            int n = allowance((int) (30 * intensity));
            for (int k = 0; k < n; k++) {
                int c = Fx.hsv(rng.nextFloat(), 0.9f, 1f);
                // Redstone dust takes its colour from the velocity arguments (red must be > 0).
                w.spawnParticle(EnumParticleTypes.REDSTONE, x + rng.nextGaussian() * 0.8, y + CY + rng.nextGaussian() * 0.6,
                        z + rng.nextGaussian() * 0.8, Math.max(0.001, (c >> 16 & 255) / 255.0), (c >> 8 & 255) / 255.0,
                        (c & 255) / 255.0);
            }
        }

        @Override
        void draw(float t, float u) {
            float grow = easeOut(span(t, 0f, 6f));
            float travel = easeOut(span(t, 8f, 30f)) * 3f;
            float fade = (1f - span(t, 12f, 32f)) * alpha;
            Fx.glow(0, CY, 0, 2.5f, 0xFFFFFF, 0.9f * alpha * (1f - span(t, 0f, 10f)));
            Fx.sphere(0, CY, 0, 0.5f + easeOut(span(t, 0f, 20f)) * 2.5f, Fx.hsv(t * 0.03f, 0.6f, 1f),
                    0.4f * alpha * (1f - span(t, 0f, 20f)), 1f);
            for (int k = 0; k < RAYS; k++) {
                int col = Fx.hsv(k / (float) RAYS, 0.85f, 1f);
                Fx.push(dir[k * 3] * travel, CY + dir[k * 3 + 1] * travel, dir[k * 3 + 2] * travel);
                Fx.rot(yaw[k], 0f, 1f, 0f);
                Fx.rot(pitch[k], 1f, 0f, 0f);
                Fx.crystal(wid[k], 0f, len[k] * grow, 3, col, 0.85f * fade);
                Fx.crystalEdges(wid[k], 0f, len[k] * grow, 3, 0xFFFFFF, 0.5f * fade, 1.5f);
                Fx.pop();
            }
            for (int k = 0; k < 3; k++) {
                float s = span(t, k * 2f, k * 2f + 16f);
                Fx.push(0, 0.6 + k * 0.5, 0);
                Fx.ring(0.5f + easeOut(s) * (3f + k), 0.3f, Fx.hsv(k / 3f + t * 0.02f, 0.8f, 1f), 0.7f * alpha * (1f - s));
                Fx.pop();
            }
        }
    }

    // ---------------------------------------------------------------- star rain

    /** Golden 3D stars fall from the sky with glowing tails and sparkle as they land. */
    static final class StarRain extends Fx3dEffect {
        private static final int STARS = 9;
        private static final int TAIL = 8;
        private static final float FALL = 10f;
        private static final int[] PALETTE = {0xFFD54A, 0xFFF6D8, 0xFFE89A};
        private final float[] sx = new float[STARS];
        private final float[] sz = new float[STARS];
        private final float[] h0 = new float[STARS];
        private final int[] delay = new int[STARS];
        private final float[] spin = new float[STARS];
        private final float[] size = new float[STARS];
        private final float[] buf = new float[TAIL * 3];
        private final float alpha;

        StarRain(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 52);
            alpha = vis(1f, i);
            for (int k = 0; k < STARS; k++) {
                double a = rng.nextDouble() * Math.PI * 2;
                double r = k == 0 ? 0 : 0.6 + rng.nextDouble() * 1.8;
                sx[k] = (float) (Math.cos(a) * r);
                sz[k] = (float) (Math.sin(a) * r);
                h0[k] = 8f + rng.nextFloat() * 4f;
                delay[k] = k * 3 + rng.nextInt(2);
                spin[k] = (rng.nextBoolean() ? 1f : -1f) * (14f + rng.nextFloat() * 14f);
                size[k] = 0.35f + rng.nextFloat() * 0.2f;
            }
        }

        private float fallY(int k, float lt) {
            float f = clamp01(lt / FALL);
            return 0.3f + h0[k] * (1f - f * f);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            for (int k = 0; k < STARS; k++) {
                if (tick == delay[k] + (int) FALL) {
                    int n = allowance((int) (6 * intensity));
                    for (int p = 0; p < n; p++) {
                        w.spawnParticle(EnumParticleTypes.FIREWORKS_SPARK, x + sx[k], y + 0.3, z + sz[k],
                                rng.nextGaussian() * 0.1, rng.nextDouble() * 0.15, rng.nextGaussian() * 0.1);
                    }
                }
            }
        }

        @Override
        void draw(float t, float u) {
            float end = 1f - span(t, 44f, 52f);
            for (int k = 0; k < STARS; k++) {
                float lt = t - delay[k];
                if (lt < 0f) continue;
                int col = PALETTE[k % PALETTE.length];
                float py = fallY(k, lt);
                float land = span(lt, FALL, FALL + 6f);
                if (lt < FALL) {
                    for (int j = 0; j < TAIL; j++) {
                        float tj = Math.max(0f, lt - j * 0.6f);
                        buf[j * 3] = sx[k];
                        buf[j * 3 + 1] = fallY(k, tj);
                        buf[j * 3 + 2] = sz[k];
                    }
                    Fx.ribbon(buf, TAIL, size[k] * 1.4f, 0f, 0xFFF2C0, 0xFF9A30, 0.8f * alpha, 0f);
                } else {
                    Fx.glow(sx[k], 0.3, sz[k], 0.3f + 1.5f * easeOut(land), col, 0.9f * alpha * (1f - land));
                    Fx.push(sx[k], 0.05, sz[k]);
                    Fx.ring(0.2f + easeOut(land) * 1.2f, 0.2f, col, 0.8f * alpha * (1f - land));
                    Fx.pop();
                }
                float s = size[k] * (1f - land);
                if (s <= 0.01f) continue;
                Fx.glow(sx[k], py, sz[k], s * 2.6f, col, 0.5f * alpha * end);
                Fx.push(sx[k], py, sz[k]);
                Fx.rot(spin[k] * lt, 0f, 1f, 0f);
                Fx.rot(20f, 1f, 0f, 0f);
                Fx.star(s, s * 0.45f, s * 0.4f, col, 0.95f * alpha * end);
                Fx.pop();
            }
        }
    }

    // ---------------------------------------------------------------- void burst

    /** Dark voxels are pulled into a void core, which detonates them outwards in a dark shock wave. */
    static final class VoidBurst extends Fx3dEffect {
        private static final int CUBES = 40;
        private static final float CY = 1.2f;
        private static final float BOOM = 18f;
        private final float[] cube = new float[CUBES * 8];
        private final float alpha;

        VoidBurst(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 42);
            alpha = vis(1f, i);
            for (int k = 0; k < CUBES; k++) {
                float ux = (float) rng.nextGaussian(), uy = (float) rng.nextGaussian(), uz = (float) rng.nextGaussian();
                float l = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                if (l < 1e-4f) {
                    ux = 1f;
                    l = 1f;
                }
                int o = k * 8;
                cube[o] = ux / l;
                cube[o + 1] = uy / l;
                cube[o + 2] = uz / l;
                cube[o + 3] = 2.6f + rng.nextFloat() * 0.8f;
                cube[o + 4] = 0.08f + rng.nextFloat() * 0.08f;
                cube[o + 5] = 0.25f + rng.nextFloat() * 0.2f;
                cube[o + 6] = 8f + rng.nextFloat() * 20f;
                cube[o + 7] = rng.nextFloat() - 0.5f;
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < 14 && tick % 2 == 0) {
                int n = allowance(Math.max(1, (int) (3 * intensity)));
                for (int k = 0; k < n; k++) {
                    w.spawnParticle(EnumParticleTypes.SPELL_MOB, x + rng.nextGaussian() * 1.5, y + CY + rng.nextGaussian(),
                            z + rng.nextGaussian() * 1.5, 0.0, 0.0, 0.0);
                }
            }
            if (tick == (int) BOOM) {
                burst(w, EnumParticleTypes.SPELL_WITCH, 24, CY, 0.3, 0.5);
                burst(w, EnumParticleTypes.PORTAL, 20, CY, 0.3, 0.8);
            }
        }

        @Override
        void draw(float t, float u) {
            float fade = (1f - span(t, 28f, 42f)) * alpha;
            for (int k = 0; k < CUBES; k++) {
                int o = k * 8;
                float d;
                if (t < BOOM) {
                    d = cube[o + 3] * (1f - easeIn(span(t, 0f, 14f))) + 0.15f;
                } else {
                    float dt = t - BOOM;
                    d = 0.3f + cube[o + 5] * dt * (1f - dt / 60f);
                }
                float sz = cube[o + 4] * smooth(span(t, 0f, 3f));
                Fx.push(cube[o] * d, CY + cube[o + 1] * d, cube[o + 2] * d);
                Fx.rot(t * cube[o + 6], cube[o + 7], 1f, 0.3f);
                Fx.normal();
                Fx.cube(sz, 0x1A0830, 0.9f * fade);
                Fx.additive();
                Fx.cube(sz * 1.3f, 0x9A40FF, 0.2f * fade);
                Fx.pop();
            }
            if (t >= 10f && t < BOOM + 1f) {
                float c = span(t, 10f, BOOM);
                float pr = 0.6f + 0.05f * (float) Math.sin(t * 3f);
                Fx.sphere(0, CY, 0, 0.9f, 0x6A20C0, 0.6f * alpha * c, 1f);
                Fx.normal();
                Fx.sphere(0, CY, 0, pr, 0x05000A, 0.95f * c, 0f);
                Fx.additive();
                Fx.billboardRing(0, CY, 0, pr + 0.05f, 0.15f, 0xC070FF, 0.8f * alpha * c);
            }
            if (t >= BOOM) {
                float s = span(t, BOOM, 40f);
                Fx.glow(0, CY, 0, 3.5f, 0xC080FF, 0.9f * alpha * (1f - span(t, BOOM, BOOM + 6f)));
                Fx.normal();
                Fx.billboardRing(0, CY, 0, 0.5f + easeOut(s) * 4.5f, 1.0f, 0x000000, 0.75f * alpha * (1f - s));
                Fx.additive();
                Fx.billboardRing(0, CY, 0, 1.0f + easeOut(s) * 4.5f, 0.3f, 0xB060FF, 0.8f * alpha * (1f - s));
            }
        }
    }

    // -------------------------------------------------------------- magic circle

    /** A rune circle in your colour draws itself on the ground, rotates, and fires a pillar of light. */
    static final class MagicCircle extends Fx3dEffect {
        private static final int GLYPHS = 12;
        private static final int STROKES = 3;
        private final float[] glyph = new float[GLYPHS * STROKES * 4];
        private final int color;
        private final int light;
        private final float alpha;

        MagicCircle(double x, double y, double z, float i, int b, int color) {
            super(x, y, z, i, b, 58);
            this.color = color & 0xFFFFFF;
            this.light = Fx.lerpColor(this.color, 0xFFFFFF, 0.5f);
            alpha = vis(1f, i);
            // Each rune: three strokes snapped to a small 3x3 grid, so they read as letters.
            for (int k = 0; k < GLYPHS * STROKES; k++) {
                int o = k * 4;
                glyph[o] = (rng.nextInt(3) - 1) * 0.09f;
                glyph[o + 1] = (rng.nextInt(3) - 1) * 0.11f;
                glyph[o + 2] = (rng.nextInt(3) - 1) * 0.09f;
                glyph[o + 3] = (rng.nextInt(3) - 1) * 0.11f;
                if (glyph[o] == glyph[o + 2] && glyph[o + 1] == glyph[o + 3]) glyph[o + 3] = -glyph[o + 1] + 0.11f;
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < 46 && tick % 2 == 0) {
                int n = allowance(Math.max(1, (int) (3 * intensity)));
                for (int k = 0; k < n; k++) {
                    double a = rng.nextDouble() * Math.PI * 2;
                    w.spawnParticle(EnumParticleTypes.ENCHANTMENT_TABLE, x + Math.cos(a) * 2.2, y + 1.5, z + Math.sin(a) * 2.2,
                            -Math.cos(a) * 0.5, -1.2, -Math.sin(a) * 0.5);
                }
            }
            if (tick == 26) burst(w, EnumParticleTypes.FIREWORKS_SPARK, 16, 0.2, 1.0, 0.2);
        }

        private void circle(float t, float drawn, float a) {
            float full = (float) (Math.PI * 2);
            Fx.arcBand(2.30f, 2.42f, 0f, full * drawn, light, 0.9f * a);
            Fx.arcBand(2.02f, 2.08f, 0f, full * drawn, color, 0.8f * a);
            for (int k = 0; k < 36; k++) {
                if (k / 36f >= drawn) break;
                float an = k * full / 36f;
                Fx.arcBand(2.1f, 2.28f, an, an + 0.04f, light, 0.7f * a);
            }
            float s = span(t, 4f, 12f);
            for (int tri = 0; tri < 2; tri++) {
                for (int e = 0; e < 3; e++) {
                    double a0 = Math.PI / 2 + tri * Math.PI + e * 2 * Math.PI / 3;
                    double a1 = a0 + 2 * Math.PI / 3;
                    float x0 = (float) Math.cos(a0) * 2.0f, z0 = (float) Math.sin(a0) * 2.0f;
                    float x1 = (float) Math.cos(a1) * 2.0f, z1 = (float) Math.sin(a1) * 2.0f;
                    Fx.stroke(x0, z0, x0 + (x1 - x0) * s, z0 + (z1 - z0) * s, 0.05f, light, 0.8f * a);
                }
            }
            for (int g = 0; g < GLYPHS; g++) {
                float ga = (float) (g * 2 * Math.PI / GLYPHS);
                float ap = span(t, 8f + g * 0.4f, 10f + g * 0.4f) * a;
                if (ap <= 0.003f) continue;
                Fx.push(Math.cos(ga) * 1.55, 0, Math.sin(ga) * 1.55);
                Fx.rot(-deg(ga) - 90f, 0f, 1f, 0f);
                for (int k = 0; k < STROKES; k++) {
                    int o = (g * STROKES + k) * 4;
                    Fx.stroke(glyph[o], glyph[o + 1], glyph[o + 2], glyph[o + 3], 0.035f, light, 0.9f * ap);
                }
                Fx.pop();
            }
            Fx.push(0, 0, 0);
            Fx.rot(-t * 10f, 0f, 1f, 0f);
            Fx.arcBand(0.98f, 1.04f, 0f, full * drawn, color, 0.8f * a);
            Fx.polygonBand(6, 0.95f, 0.04f, 0f, light, 0.7f * a * s);
            Fx.polygonBand(4, 0.68f, 0.04f, 0f, color, 0.7f * a * s);
            Fx.pop();
        }

        @Override
        void draw(float t, float u) {
            float drawn = easeOut(span(t, 0f, 10f));
            float env = (1f - span(t, 46f, 58f)) * alpha;
            Fx.push(0, 0.04, 0);
            Fx.disc(2.5f, color, 0.25f * env, 0f);
            Fx.rot(t * 3f, 0f, 1f, 0f);
            circle(t, drawn, env);
            Fx.pop();
            if (t >= 26f) {
                float p = easeOut(span(t, 26f, 32f));
                Fx.beam(1.15f, 1.15f, 9f * p, light, 0.35f * env, 0f);
                Fx.beam(0.4f, 0.4f, 10f * p, 0xFFFFFF, 0.5f * env, 0f);
            }
            if (t >= 28f) {
                float h = smooth(span(t, 28f, 34f));
                Fx.push(0, 2.6 + 0.1 * Math.sin(t * 0.2), 0);
                Fx.rot(-t * 5f, 0f, 1f, 0f);
                Fx.scale(0.55f, 0.55f, 0.55f);
                circle(t, 1f, env * h);
                Fx.pop();
            }
        }
    }

    // --------------------------------------------------------------- flame wings

    /** Burning wings unfold behind the victim, beat once, then burst into embers. */
    static final class FlameWings extends Fx3dEffect {
        private static final int FEATHERS = 8;
        private final float[] len = new float[FEATHERS];
        private final float alpha;
        private final float hx;
        private final float hz;

        FlameWings(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 46);
            alpha = vis(1f, i);
            for (int f = 0; f < FEATHERS; f++) {
                float m = f / (float) (FEATHERS - 1);
                len[f] = 1.15f + 0.85f * (float) Math.sin(Math.PI * (0.25 + 0.6 * m));
            }
            double yr = Math.toRadians(viewYaw);
            hx = (float) Math.cos(yr);
            hz = (float) Math.sin(yr);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick < 30 && tick % 2 == 0) {
                int n = allowance(Math.max(1, (int) (2 * intensity)));
                for (int k = 0; k < n; k++) {
                    double side = (rng.nextBoolean() ? 1 : -1) * (0.4 + rng.nextDouble() * 1.4);
                    w.spawnParticle(EnumParticleTypes.FLAME, x + hx * side, y + 1.4 + rng.nextDouble() * 1.6, z + hz * side,
                            0, 0.02, 0);
                }
            }
            if (tick == 30) {
                burst(w, EnumParticleTypes.FLAME, 20, 1.6, 1.0, 0.08);
                burst(w, EnumParticleTypes.LAVA, 6, 1.6, 0.6, 0.0);
            }
        }

        @Override
        void draw(float t, float u) {
            float spread = easeOutBack(span(t, 0f, 10f));
            float fs = span(t, 12f, 24f);
            float flap = fs > 0f && fs < 1f ? (float) Math.sin(fs * Math.PI * 2) * 14f : 0f;
            float fade = (1f - span(t, 30f, 46f)) * alpha * smooth(span(t, 0f, 2f));
            float lift = smooth(span(t, 30f, 46f)) * 1.5f;
            Fx.push(0, 1.35 + lift, 0);
            Fx.rot(-viewYaw, 0f, 1f, 0f);
            for (int side = -1; side <= 1; side += 2) {
                Fx.push(side * 0.15, 0, 0.3);
                for (int f = 0; f < FEATHERS; f++) {
                    float aim = 15f + f * (100f / (FEATHERS - 1));
                    float aEff = -70f + (aim + 70f) * spread + flap;
                    float l = len[f] * (0.3f + 0.7f * Math.min(1f, spread));
                    Fx.push(0, 0, 0);
                    Fx.rot(side > 0 ? -(90f - aEff) : (90f - aEff), 0f, 0f, 1f);
                    Fx.rot(side * 8f, 0f, 1f, 0f);
                    Fx.feather(l, 0.2f, -side * 0.15f * l, 0xFFE07A, 0xFF3A0A, 0.85f * fade);
                    Fx.feather(l * 0.95f, 0.07f, -side * 0.15f * l, 0xFFFFFF, 0xFFB040, 0.7f * fade);
                    Fx.pop();
                }
                Fx.pop();
            }
            Fx.pop();
            Fx.glow(0, 1.4 + lift, 0, 1.8f, 0xFF8A2A, 0.6f * fade);
        }
    }

    // -------------------------------------------------------- shattered dimension

    /** Space cracks open around the victim and breaks into glassy shards that show the void behind. */
    static final class ShatteredDimension extends Fx3dEffect {
        private static final int SHARDS = 26;
        private static final int CORNERS = 5;
        private static final int CRACKS = 8;
        private static final int CP = 6;
        private static final float CY = 1.3f;
        private final float[] shard = new float[SHARDS * 7];
        private final float[] corners = new float[SHARDS * CORNERS * 2];
        private final float[] scratch = new float[CORNERS * 2];
        private final float[] crack = new float[CRACKS * CP * 2];
        private final float[] buf = new float[CP * 3];
        private final float alpha;

        ShatteredDimension(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 46);
            alpha = vis(1f, i);
            for (int k = 0; k < SHARDS; k++) {
                float ux = (float) rng.nextGaussian(), uy = (float) rng.nextGaussian(), uz = (float) rng.nextGaussian();
                float l = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                if (l < 1e-4f) {
                    uy = 1f;
                    l = 1f;
                }
                ux /= l;
                uy /= l;
                uz /= l;
                int o = k * 7;
                shard[o] = ux;
                shard[o + 1] = uy;
                shard[o + 2] = uz;
                shard[o + 3] = 1.5f + rng.nextFloat() * 0.7f;
                shard[o + 4] = (rng.nextBoolean() ? 1f : -1f) * (2f + rng.nextFloat() * 6f);
                shard[o + 5] = rng.nextFloat();
                shard[o + 6] = 0.8f + rng.nextFloat() * 0.6f;
                for (int c = 0; c < CORNERS; c++) {
                    double a = (c + 0.2 + rng.nextFloat() * 0.6) * 2 * Math.PI / CORNERS;
                    float r = 0.28f + rng.nextFloat() * 0.2f;
                    corners[(k * CORNERS + c) * 2] = (float) Math.cos(a) * r;
                    corners[(k * CORNERS + c) * 2 + 1] = (float) Math.sin(a) * r;
                }
            }
            for (int c = 0; c < CRACKS; c++) {
                double a = c * 2 * Math.PI / CRACKS + rng.nextGaussian() * 0.2;
                float px = 0f, py = 0f;
                for (int p = 0; p < CP; p++) {
                    crack[(c * CP + p) * 2] = px;
                    crack[(c * CP + p) * 2 + 1] = py;
                    float step = 0.25f + rng.nextFloat() * 0.2f;
                    double ja = a + rng.nextGaussian() * 0.5;
                    px += (float) Math.cos(ja) * step;
                    py += (float) Math.sin(ja) * step;
                }
            }
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 5) burst(w, EnumParticleTypes.FIREWORKS_SPARK, 16, CY, 0.4, 0.3);
            if (tick == 22) burst(w, EnumParticleTypes.SPELL_INSTANT, 12, CY, 0.8, 0.2);
        }

        @Override
        void draw(float t, float u) {
            if (t < 12f) {
                float grow = easeOut(span(t, 0f, 5f));
                float fade = (1f - span(t, 6f, 12f)) * alpha;
                for (int c = 0; c < CRACKS; c++) {
                    for (int p = 0; p < CP; p++) {
                        float a = crack[(c * CP + p) * 2] * grow * 1.6f;
                        float b = crack[(c * CP + p) * 2 + 1] * grow * 1.6f;
                        buf[p * 3] = Fx.rx * a + Fx.ux * b;
                        buf[p * 3 + 1] = CY + Fx.ry * a + Fx.uy * b;
                        buf[p * 3 + 2] = Fx.rz * a + Fx.uz * b;
                    }
                    Fx.ribbon(buf, CP, 0.35f, 0.1f, 0x7FC8FF, 0x7FC8FF, 0.35f * fade, 0.15f * fade);
                    Fx.ribbon(buf, CP, 0.08f, 0.02f, 0xFFFFFF, 0x9FE8FF, 0.95f * fade, 0.5f * fade);
                }
                Fx.glow(0, CY, 0, 1.2f, 0xFFFFFF, 0.8f * fade);
            }
            if (t < 5f) return;
            float appear = easeOutBack(span(t, 5f, 9f));
            float fly = easeIn(span(t, 22f, 46f)) * 6f;
            float fade = (1f - span(t, 32f, 46f)) * alpha * smooth(span(t, 5f, 6.5f));
            Fx.glow(0, CY, 0, 3f, 0x6A7CFF, 0.25f * fade);
            for (int k = 0; k < SHARDS; k++) {
                int o = k * 7;
                float ux = shard[o], uy = shard[o + 1], uz = shard[o + 2];
                float d = shard[o + 3] * appear * 0.9f + fly * shard[o + 6] + 0.05f * (float) Math.sin(t * 0.2f + k);
                int hue = Fx.hsv(shard[o + 5] + t * 0.01f, 0.55f, 1f);
                System.arraycopy(corners, k * CORNERS * 2, scratch, 0, CORNERS * 2);
                Fx.push(ux * d, CY + uy * d, uz * d);
                Fx.rot(deg(Math.atan2(ux, uz)), 0f, 1f, 0f);
                Fx.rot(-deg(Math.asin(Math.max(-1f, Math.min(1f, uy)))), 1f, 0f, 0f);
                Fx.rot(shard[o + 4] * t, 0f, 0f, 1f);
                Fx.normal();
                Fx.poly2d(scratch, CORNERS, 0x0A0614, 0.65f * fade);
                Fx.additive();
                Fx.poly2d(scratch, CORNERS, hue, 0.18f * fade);
                Fx.poly2dEdges(scratch, CORNERS, Fx.lerpColor(hue, 0xFFFFFF, 0.5f), 0.95f * fade, 2f);
                Fx.pop();
            }
        }
    }

    // ---------------------------------------------------------------- neon pulse

    /** Neon hexagon pulses race across the ground while equaliser bars in your colour bounce around the victim. */
    static final class NeonPulse extends Fx3dEffect {
        private static final int PULSES = 5;
        private static final int BARS = 14;
        private final int color;
        private final int second;
        private final float alpha;

        NeonPulse(double x, double y, double z, float i, int b, int color) {
            super(x, y, z, i, b, 42);
            this.color = color & 0xFFFFFF;
            // Rotate the channels for a contrasting partner hue (pink -> cyan, etc.).
            this.second = (this.color & 0xFF) << 16 | (this.color >> 16 & 0xFF) << 8 | (this.color >> 8 & 0xFF);
            alpha = vis(1f, i);
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick % 5 == 0 && tick / 5 < PULSES) burst(w, EnumParticleTypes.FIREWORKS_SPARK, 6, 0.2, 0.3, 0.15);
        }

        @Override
        void draw(float t, float u) {
            float env = smooth(span(t, 0f, 4f)) * (1f - span(t, 30f, 42f)) * alpha;
            Fx.push(0, 0.05, 0);
            for (int k = 0; k < PULSES; k++) {
                float t0 = k * 5f;
                if (t < t0) break;
                float r = 0.3f + (t - t0) * 0.3f;
                float a = 1f - span(t, t0, t0 + 20f);
                int col = k % 2 == 0 ? color : second;
                Fx.polygonBand(6, r, 0.35f, (float) (Math.PI / 6), col, 0.25f * alpha * a);
                Fx.polygonBand(6, r, 0.1f, (float) (Math.PI / 6), col, 0.9f * alpha * a);
            }
            Fx.pop();
            for (int k = 0; k < BARS; k++) {
                double ba = k * 2 * Math.PI / BARS;
                float bx = (float) Math.cos(ba) * 1.5f;
                float bz = (float) Math.sin(ba) * 1.5f;
                float h = (0.4f + 0.6f * (float) Math.abs(Math.sin(t * 0.35f + k * 0.9f))) * 2.2f
                        * smooth(span(t, k * 0.3f, k * 0.3f + 4f));
                int col = k % 2 == 0 ? color : second;
                Fx.push(bx, 0, bz);
                Fx.beam(0.18f, 0.18f, h, col, 0.25f * env, 0.25f * env);
                Fx.beam(0.06f, 0.06f, h, Fx.lerpColor(col, 0xFFFFFF, 0.4f), 0.9f * env, 0.9f * env);
                Fx.pop();
                Fx.glow(bx, h, bz, 0.35f, 0xFFFFFF, 0.8f * env);
            }
            for (int k = 0; k < 3; k++) {
                float yy = (t * 0.12f + k * 1.2f) % 3.6f;
                Fx.push(0, yy, 0);
                Fx.polygonBand(6, 0.8f, 0.06f, t * 0.05f, k % 2 == 0 ? second : color,
                        0.85f * env * (1f - yy / 3.6f));
                Fx.pop();
            }
            Fx.glow(0, 1.0, 0, 1.5f, color, 0.5f * env);
        }
    }

    // ------------------------------------------------------------- kestrel talon

    /**
     * Kestrel's signature: a falcon of light dives through the victim, leaves
     * three talon slashes and a flurry of kestrel-coloured feathers.
     */
    static final class KestrelTalon extends Fx3dEffect {
        private static final int RUST = 0xE0662A;
        private static final int CREAM = 0xFFE8C8;
        private static final int SLATE = 0x6E8CB0;
        private static final int TRAIL = 10;
        private static final int FEATHERS = 16;
        /** Falcon silhouette (x, z), nose towards +z, in angular order around the origin. */
        private static final float[] BIRD = {
                0f, 0.55f, 0.35f, 0.15f, 1.45f, -0.45f, 0.5f, -0.25f, 0.18f, -0.75f,
                -0.18f, -0.75f, -0.5f, -0.25f, -1.45f, -0.45f, -0.35f, 0.15f};
        private final float[] p0 = new float[3];
        private final float[] p1 = new float[3];
        private final float[] p2 = new float[3];
        private final float[] pt = new float[3];
        private final float[] buf = new float[TRAIL * 3];
        private final float[] feather = new float[FEATHERS * 6];
        private final float alpha;

        KestrelTalon(double x, double y, double z, float i, int b) {
            super(x, y, z, i, b, 48);
            alpha = vis(1f, i);
            double yr = Math.toRadians(viewYaw);
            float lx = (float) -Math.sin(yr);
            float lz = (float) Math.cos(yr);
            p0[0] = -lx * 6f;
            p0[1] = 5.5f;
            p0[2] = -lz * 6f;
            p1[0] = 0f;
            p1[1] = -2.6f;
            p1[2] = 0f;
            p2[0] = lx * 6f;
            p2[1] = 5f;
            p2[2] = lz * 6f;
            for (int k = 0; k < FEATHERS; k++) {
                int o = k * 6;
                feather[o] = (float) rng.nextGaussian() * 0.4f;
                feather[o + 1] = 1.2f + rng.nextFloat() * 0.6f;
                feather[o + 2] = (float) rng.nextGaussian() * 0.4f;
                feather[o + 3] = rng.nextFloat() * 6.28f;
                feather[o + 4] = rng.nextFloat() * 360f;
                feather[o + 5] = 0.04f + rng.nextFloat() * 0.03f;
            }
        }

        private void bezier(float s, float[] out) {
            float a = (1 - s) * (1 - s);
            float b = 2 * s * (1 - s);
            float c = s * s;
            for (int k = 0; k < 3; k++) out[k] = a * p0[k] + b * p1[k] + c * p2[k];
        }

        @Override
        void onTick(WorldClient w, int tick) {
            if (tick == 5 || tick == 6 || tick == 8) burst(w, EnumParticleTypes.CRIT, 8, 1.2, 0.3, 0.4);
        }

        @Override
        void draw(float t, float u) {
            // Falcon dive.
            if (t < 11f) {
                float s = span(t, 0f, 10f);
                float fa = smooth(span(t, 0f, 1.5f)) * (1f - span(s, 0.85f, 1f)) * alpha;
                for (int j = 0; j < TRAIL; j++) {
                    bezier(Math.max(0f, s - j * 0.025f), pt);
                    buf[j * 3] = pt[0];
                    buf[j * 3 + 1] = pt[1];
                    buf[j * 3 + 2] = pt[2];
                }
                Fx.ribbon(buf, TRAIL, 0.9f, 0.05f, 0xFFB070, RUST, 0.6f * fa, 0f);
                bezier(s, pt);
                float px = pt[0], py = pt[1], pz = pt[2];
                bezier(Math.min(1f, s + 0.01f), pt);
                float dx = pt[0] - px, dy = pt[1] - py, dz = pt[2] - pz;
                float hl = (float) Math.sqrt(dx * dx + dz * dz);
                float beat = 1f + 0.15f * (float) Math.sin(t * 2.5f);
                Fx.glow(px, py, pz, 1.6f, RUST, 0.6f * fa);
                Fx.push(px, py, pz);
                Fx.rot(deg(Math.atan2(dx, dz)), 0f, 1f, 0f);
                Fx.rot(-deg(Math.atan2(dy, hl)), 1f, 0f, 0f);
                Fx.scale(beat, 1f, 1f);
                Fx.polyXZ(BIRD, BIRD.length / 2, CREAM, RUST, 0.95f * fa, 0.75f * fa);
                Fx.pop();
            }
            Fx.glow(0, 1.3, 0, 2.5f, 0xFFB070, 0.7f * alpha * span(t, 4f, 5.5f) * (1f - span(t, 6f, 12f)));

            // Three talon slashes in the plane facing the viewer.
            Fx.push(0, 1.3, 0);
            Fx.rot(-viewYaw, 0f, 1f, 0f);
            Fx.rot(90f, 1f, 0f, 0f);
            for (int k = 0; k < 3; k++) {
                float t0 = 5f + k * 1.5f;
                if (t < t0) break;
                float p = easeOut(span(t, t0, t0 + 2.5f));
                float a = (1f - span(t, t0 + 3f, t0 + 14f)) * alpha;
                float r = 1.1f + k * 0.12f;
                float from = (float) Math.toRadians(200);
                float to = from + (float) Math.toRadians(140) * p;
                Fx.push((k - 1) * 0.35f, 0, -0.6f);
                Fx.rot(35f, 0f, 1f, 0f);
                Fx.arcBand(r - 0.1f, r + 0.1f, from, to, RUST, 0.45f * a);
                Fx.arcBand(r - 0.03f, r + 0.03f, from, to, 0xFFF2D8, 0.95f * a);
                Fx.pop();
            }
            Fx.pop();

            // Feathers drift down, fluttering.
            if (t >= 9f) {
                float ft = t - 9f;
                float fa = smooth(span(ft, 0f, 1.5f)) * (1f - span(t, 36f, 48f)) * alpha;
                for (int k = 0; k < FEATHERS; k++) {
                    int o = k * 6;
                    float py = Math.max(0.05f, feather[o + 1] + 0.1f - 0.045f * ft);
                    float sway = (float) Math.sin(ft * 0.25f + feather[o + 3]);
                    int base = k % 3 == 0 ? CREAM : (k % 3 == 1 ? RUST : SLATE);
                    Fx.push(feather[o] + sway * 0.4f + feather[o] * ft * 0.03f, py, feather[o + 2] + feather[o + 2] * ft * 0.03f);
                    Fx.rot(feather[o + 4] + ft * 7f, 0f, 1f, 0f);
                    Fx.rot(sway * 40f, 0f, 0f, 1f);
                    Fx.normal();
                    Fx.feather(0.35f, 0.07f, 0.04f, base, 0x2A1E18, 0.9f * fa);
                    Fx.additive();
                    Fx.pop();
                }
            }
        }
    }
}
