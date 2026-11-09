package com.kestrel.openmap;

/**
 * Vanilla 1.8.9 projectile flight, for aiming: a thrown snowball
 * ({@code EntityThrowable}: speed 1.5, then each tick move, drag 0.99, gravity
 * 0.03) and a fishing hook ({@code EntityFishHook} in air: speed 1.5, then each
 * tick move, gravity 0.04, drag 0.92). Only the opponent's own launch is
 * simulated; the target's future is a guess from its current velocity.
 */
public final class Ballistics {
    public enum Kind {
        SNOWBALL(1.5, 0.03, 0.99, true),
        HOOK(1.5, 0.04, 0.92, false);

        final double speed;
        final double gravity;
        final double drag;
        /** Throwable: drag first, then gravity; hook: gravity first, then drag. */
        final boolean dragThenGravity;

        Kind(double speed, double gravity, double drag, boolean dragThenGravity) {
            this.speed = speed;
            this.gravity = gravity;
            this.drag = drag;
            this.dragThenGravity = dragThenGravity;
        }
    }

    /** A solution: the pitch to throw at and the flight time (ticks) to the target. */
    public static final class Shot {
        public final float pitch;
        public final int ticks;

        Shot(float pitch, int ticks) {
            this.pitch = pitch;
            this.ticks = ticks;
        }
    }

    private Ballistics() {
    }

    /**
     * Height (relative to launch) when the projectile has travelled {@code dist}
     * horizontally, launched at {@code pitchDeg} (Minecraft pitch: negative is
     * up). Returns NaN when it never gets that far within {@code maxTicks}; the
     * flight time goes into {@code ticksOut[0]}.
     */
    static double heightAt(Kind k, double pitchDeg, double dist, int maxTicks, int[] ticksOut) {
        double p = Math.toRadians(pitchDeg);
        double vh = Math.cos(p) * k.speed;
        double vy = -Math.sin(p) * k.speed;
        double h = 0;
        double y = 0;
        for (int t = 1; t <= maxTicks; t++) {
            double nh = h + vh;
            double ny = y + vy;
            if (nh >= dist) {
                double f = vh == 0 ? 0 : (dist - h) / vh;
                if (ticksOut != null) ticksOut[0] = t;
                return y + (ny - y) * f;
            }
            h = nh;
            y = ny;
            if (k.dragThenGravity) {
                vh *= k.drag;
                vy *= k.drag;
                vy -= k.gravity;
            } else {
                vy -= k.gravity;
                vh *= k.drag;
                vy *= k.drag;
            }
            if (vh < 1e-4) break;
        }
        return Double.NaN;
    }

    /**
     * The flattest pitch that reaches a point {@code dist} away horizontally and
     * {@code dy} higher, or null when out of range.
     */
    public static Shot solve(Kind k, double dist, double dy, int maxTicks) {
        int[] ticks = new int[1];
        double lo = -60;  // steep up
        double hi = 60;   // steep down
        // Height at dist falls as pitch grows; find the reachable band first.
        double bestErr = Double.MAX_VALUE;
        float best = 0;
        int bestTicks = 0;
        for (double pitch = -45; pitch <= 45; pitch += 3) {
            double hgt = heightAt(k, pitch, dist, maxTicks, ticks);
            if (Double.isNaN(hgt)) continue;
            double err = Math.abs(hgt - dy);
            if (err < bestErr) {
                bestErr = err;
                best = (float) pitch;
                bestTicks = ticks[0];
            }
        }
        if (bestErr == Double.MAX_VALUE) return null;
        lo = best - 3;
        hi = best + 3;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            double hgt = heightAt(k, mid, dist, maxTicks, ticks);
            if (Double.isNaN(hgt) || hgt < dy) hi = mid; else lo = mid;
        }
        double pitch = (lo + hi) / 2;
        double hgt = heightAt(k, pitch, dist, maxTicks, ticks);
        if (Double.isNaN(hgt) || Math.abs(hgt - dy) > 0.6) return null;
        return new Shot((float) pitch, ticks[0]);
    }
}
