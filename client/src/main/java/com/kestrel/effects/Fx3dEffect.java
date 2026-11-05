package com.kestrel.effects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;

import java.util.Random;

/**
 * Base of the advanced (3D geometry) kill effects. Subclasses set up their
 * random layout once in the constructor (into fixed arrays), react to ticks in
 * {@link #onTick}, and draw in {@link #draw} with the {@link Fx} toolkit; the
 * frame loop allocates nothing. {@link #render} brackets the drawing with
 * {@link Fx#begin}/{@link Fx#end}, so GL state is always restored, even if a
 * draw call throws.
 */
abstract class Fx3dEffect extends ActiveEffect {
    final Random rng;
    /** Yaw (degrees) of the local player when the effect started; faces flat effects towards them. */
    final float viewYaw;
    private final int life;

    Fx3dEffect(double x, double y, double z, float intensity, int budget, int life) {
        super(x, y, z, intensity, budget);
        this.life = life;
        this.rng = new Random(Double.doubleToLongBits(x * 31 + z) ^ System.nanoTime());
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        this.viewYaw = p == null ? 0f : p.rotationYaw;
    }

    @Override
    final void tick(WorldClient w) {
        onTick(w, age);
        age++;
    }

    /** Called once per client tick with the tick number (0 on the first tick). */
    void onTick(WorldClient w, int tick) {
    }

    @Override
    final void render(float pt, double cx, double cy, double cz) {
        float t = age + pt;
        if (t >= life) return;
        Fx.begin(x, y, z, cx, cy, cz);
        try {
            draw(t, t / life);
        } finally {
            Fx.end();
        }
    }

    /**
     * Draw at time {@code t} (ticks since start, fractional) and normalised
     * progress {@code u} in [0, 1). Origin is the effect position (the victim's feet).
     */
    abstract void draw(float t, float u);

    @Override
    final int lifetimeTicks() {
        return life;
    }

    /** Spawn up to {@code wanted} particles of a kind around the origin (within the particle budget). */
    final void burst(WorldClient w, EnumParticleTypes type, int wanted, double oy, double spread, double speed) {
        int n = allowance((int) (wanted * intensity));
        for (int k = 0; k < n; k++) {
            w.spawnParticle(type, x + rng.nextGaussian() * spread, y + oy + rng.nextGaussian() * spread * 0.5,
                    z + rng.nextGaussian() * spread, rng.nextGaussian() * speed, rng.nextDouble() * speed,
                    rng.nextGaussian() * speed);
        }
    }

    // --------------------------------------------------------------- easing

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 0..1 over [a, b] of {@code t}, unclamped outside. */
    static float span(float t, float a, float b) {
        return clamp01((t - a) / (b - a));
    }

    static float easeOut(float v) {
        float i = 1f - clamp01(v);
        return 1f - i * i * i;
    }

    static float easeIn(float v) {
        float c = clamp01(v);
        return c * c * c;
    }

    static float smooth(float v) {
        float c = clamp01(v);
        return c * c * (3f - 2f * c);
    }

    /** Overshooting ease-out (pops slightly past 1 and settles). */
    static float easeOutBack(float v) {
        float c = clamp01(v) - 1f;
        return 1f + c * c * (2.70158f * c + 1.70158f);
    }

    /** Fade-in over [0, in] and fade-out over [1 - out, 1] of normalised time. */
    static float envelope(float u, float in, float out) {
        return smooth(u / in) * (1f - smooth((u - (1f - out)) / out));
    }

    static float vis(float alpha, float intensity) {
        return alpha * Math.min(1f, 0.55f + 0.45f * intensity);
    }

    static float deg(double rad) {
        return (float) Math.toDegrees(rad);
    }
}
