package com.kestrel.sumo;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Random;

/**
 * Decision making of the Sumo opponent, once per game tick. Pure (no Minecraft
 * types): it sees positions and produces the same inputs a player has, which
 * the entity then plays through vanilla movement and combat rules:
 * forward/back and strafe keys, sprint, jump, one click, and a look direction
 * limited to a human turn rate.
 *
 * <p>What it does: sees the player with a reaction delay, aims with leading and
 * a wandering error, keeps hitting range, strafes both ways with varied timing,
 * W-taps to reset sprint after a sprint hit, sprint-jumps to close distance,
 * guards its own edge, tries to stand so the player is between it and the
 * nearest edge, and learns the player's strafe direction and rhythm, jumping,
 * edge habits and hitting range ({@link PlayerProfile}).
 */
public final class SumoBrain {
    /** Survival reach (from the eyes); the entity enforces it with a ray test. */
    public static final double REACH = 3.0;
    private static final double EYE_HEIGHT = 1.62;

    /** One body as the brain sees it. Velocities are per tick. */
    public static final class Body {
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public boolean onGround;
        public int hurtTime;

        public Body set(double x, double y, double z, float yaw, float pitch, boolean onGround, int hurtTime) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
            this.hurtTime = hurtTime;
            return this;
        }
    }

    /** The inputs for this tick. {@code forward}/{@code strafe} are -1, 0 or 1 (+strafe = left, as in Minecraft). */
    public static final class Controls {
        public float forward;
        public float strafe;
        public boolean jump;
        public boolean sprint;
        public boolean attack;
        /** Look direction after this tick's (rate-limited) turn. */
        public float yaw;
        public float pitch;
    }

    private final PlayerProfile profile;
    private final Random rng;
    private SumoDifficulty difficulty;
    private ArenaShape arena = ArenaShape.square();
    private Playstyle style = Playstyle.BALANCED;
    private final Controls out = new Controls();

    /** Delayed perception: oldest first, {x, y, z, onGround}. */
    private final ArrayDeque<double[]> seen = new ArrayDeque<double[]>();
    private int strafeDir = 1;
    private int strafeTimer;
    private int wtapTicks;
    private int lateResprint;
    private double clickTimer;
    private double noiseYaw;
    private double noisePitch;
    private int jumpCooldown;
    private boolean waitForLanding;

    // Learning (undelayed observation of the player).
    private boolean haveLast;
    private double lastTx;
    private double lastTy;
    private double lastTz;
    private boolean lastTOnGround;
    private int lateralDir;
    private int lateralRun;

    public SumoBrain(SumoDifficulty difficulty, PlayerProfile profile, Random rng) {
        this.difficulty = difficulty;
        this.profile = profile;
        this.rng = rng;
    }

    public void setDifficulty(SumoDifficulty difficulty) {
        this.difficulty = difficulty;
    }

    public SumoDifficulty difficulty() {
        return difficulty;
    }

    /** The platform it fights on (scanned at the start of each match). */
    public void setArena(ArenaShape arena) {
        this.arena = arena == null ? ArenaShape.square() : arena;
    }

    public void setPlaystyle(Playstyle style) {
        this.style = style == null ? Playstyle.BALANCED : style;
    }

    public Playstyle playstyle() {
        return style;
    }

    /** Forget everything that belongs to one round (not what was learned). */
    public void resetRound() {
        seen.clear();
        strafeTimer = 0;
        wtapTicks = 0;
        lateResprint = 0;
        clickTimer = 0;
        noiseYaw = 0;
        noisePitch = 0;
        jumpCooldown = 0;
        waitForLanding = false;
        if (haveLast && lateralDir != 0) profile.recordStrafeRun(lateralRun);
        haveLast = false;
        lateralDir = 0;
        lateralRun = 0;
    }

    /** The opponent landed a hit; {@code sprintHit} when it was a sprint (knockback) hit. */
    public void onHitLanded(boolean sprintHit) {
        if (!sprintHit) return;
        if (rng.nextDouble() < difficulty.wtapChance()) {
            wtapTicks = 1 + rng.nextInt(2);
            lateResprint = 0;
        } else {
            // Sloppy: keeps holding W without sprint for a while, then resets late.
            lateResprint = 8 + rng.nextInt(12);
        }
    }

    /** The player landed a hit from {@code range} blocks. */
    public void onHitTaken(double range) {
        profile.recordHitRange(range);
    }

    public Controls tick(Body self, Body target) {
        observe(self, target);

        // Perception, delayed by the reaction time.
        seen.addLast(new double[]{target.x, target.y, target.z, target.onGround ? 1 : 0});
        int keep = difficulty.reactionTicks() + 2;
        while (seen.size() > keep) seen.removeFirst();
        Iterator<double[]> it = seen.iterator();
        double[] t = it.next();
        double[] tNext = it.hasNext() ? it.next() : t;
        double tvx = tNext[0] - t[0];
        double tvz = tNext[2] - t[2];
        boolean targetAir = t[3] < 0.5;

        double dx = t[0] - self.x;
        double dz = t[2] - self.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double ux = dist > 1e-6 ? dx / dist : -Math.sin(Math.toRadians(self.yaw));
        double uz = dist > 1e-6 ? dz / dist : Math.cos(Math.toRadians(self.yaw));
        // Left of the line towards the target (Minecraft strafe +).
        double lx = uz;
        double lz = -ux;
        double w = difficulty.adaptWeight();

        // ---- Aim: lead the target, anticipate a strafe switch, wander a little.
        double lat = tvx * lx + tvz * lz;
        double radial = tvx * ux + tvz * uz;
        double expectedRun = profile.expectedStrafeRun();
        if (expectedRun > 0 && lateralDir != 0 && lateralRun >= expectedRun * 0.9) {
            lat *= 1.0 - 1.2 * w; // expects the player to switch direction about now
        } else if (Math.abs(lat) < 0.02) {
            lat += profile.preferredLateral() * 0.05 * w;
        }
        double lead = difficulty.leadTicks() + difficulty.reactionTicks();
        double px = t[0] + (radial * ux + lat * lx) * lead;
        double pz = t[2] + (radial * uz + lat * lz) * lead;
        double noise = difficulty.aimNoiseDeg();
        noiseYaw = noiseYaw * 0.85 + rng.nextGaussian() * noise * 0.35;
        noisePitch = noisePitch * 0.85 + rng.nextGaussian() * noise * 0.15;
        float maxTurn = difficulty.maxTurnDeg();
        float wantYaw = (float) (yawTo(self.x, self.z, px, pz) + noiseYaw);
        float yaw = self.yaw + clamp(wrap(wantYaw - self.yaw), -maxTurn, maxTurn);
        double eye = self.y + EYE_HEIGHT;
        double aimY = t[1] + 1.1;
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(aimY - eye, Math.max(dist, 0.3))) + noisePitch);
        float pitch = clamp(self.pitch + clamp(wrap(wantPitch - self.pitch), -maxTurn, maxTurn), -90f, 90f);

        // ---- Movement, as a world-space direction.
        double selfEdge = arena.edgeDistance(self.x, self.z);
        double[] selfOut = arena.nearestEdgeNormal(self.x, self.z);
        double caution = clampD(difficulty.edgeCaution() + style.cautionOffset, 0.0, 1.0);
        double safe = 1.5 + 1.5 * caution;
        double range = clampD((profile.typicalHitRange() > 0
                ? clampD(profile.typicalHitRange() + 0.15 * w, 2.4, 3.0) : 2.7) + style.rangeOffset, 2.2, 3.1);

        // Jumpy players: hold off while they are in the air and hit them as they land.
        if (!targetAir) waitForLanding = false;
        else if (!waitForLanding && dist < 4.0 && profile.jumpsPerSecond() > 0.4) {
            waitForLanding = rng.nextDouble() < w;
        }

        double approach;
        if (dist > range + 0.5) approach = 1.0;
        else if (dist > 2.0) approach = target.hurtTime > 0 ? 1.0 : 0.6;
        else approach = target.hurtTime > 0 ? 0.8 : -0.4; // too close: make space
        if (dist > 2.0) approach += style.approachBias;
        if (waitForLanding) approach = Math.min(approach, 0.0);

        if (--strafeTimer <= 0) pickStrafe(self, t, lx, lz, w);
        // Never let the strafe carry it towards its own edge.
        if (selfEdge < safe && strafeDir * (lx * selfOut[0] + lz * selfOut[1]) > 0.3) {
            strafeDir = -strafeDir;
            strafeTimer = Math.max(strafeTimer, 6);
        }
        double strafeWeight = Math.min((dist < 5 ? 0.85 : 0.4) * style.strafeMul, 1.2);
        double mx = ux * approach + lx * strafeDir * strafeWeight;
        double mz = uz * approach + lz * strafeDir * strafeWeight;

        double cx = arena.safeX() - self.x;
        double cz = arena.safeZ() - self.z;
        double cl = Math.max(Math.sqrt(cx * cx + cz * cz), 1e-6);
        cx /= cl;
        cz /= cl;
        if (selfEdge < safe) {
            double k = (safe - selfEdge) / safe * (1 + 2 * caution);
            mx += cx * k * 1.5;
            mz += cz * k * 1.5;
            double outward = mx * selfOut[0] + mz * selfOut[1];
            if (outward > 0) {
                mx -= selfOut[0] * outward * caution;
                mz -= selfOut[1] * outward * caution;
            }
        }
        boolean danger = selfEdge < 1.2 || (self.hurtTime > 0 && selfEdge < safe);
        if (danger) {
            mx = cx;
            mz = cz;
        }
        double ml = Math.sqrt(mx * mx + mz * mz);
        if (ml > 1e-6) {
            mx /= ml;
            mz /= ml;
        }

        // Keys relative to where it faces after this tick's turn.
        double yr = Math.toRadians(yaw);
        double fx = -Math.sin(yr);
        double fz = Math.cos(yr);
        double sx = Math.cos(yr);
        double sz = Math.sin(yr);
        float forward = key(mx * fx + mz * fz);
        float strafe = key(mx * sx + mz * sz);

        // ---- Sprint resets.
        if (lateResprint > 0 && --lateResprint == 0) wtapTicks = 1;
        boolean wtap = wtapTicks > 0;
        if (wtap) {
            wtapTicks--;
            if (forward > 0) forward = 0; // let go of W for a tick or two
        }

        // ---- Jumping: sprint-jump in from range, a little more against jumpy players.
        boolean jump = false;
        if (jumpCooldown > 0) jumpCooldown--;
        if (self.onGround && jumpCooldown == 0 && !danger && selfEdge > safe && forward > 0) {
            double chance = 0;
            if (dist > 3.4 && dist < 6.5) chance = 0.05 + 0.07 * difficulty.skill();
            chance += Math.min(profile.jumpsPerSecond(), 2.0) * 0.01 * w;
            chance *= style.jumpMul;
            // A jump carries it ~2.5 blocks: only if it lands well inside.
            double landX = self.x + mx * 2.5;
            double landZ = self.z + mz * 2.5;
            if (arena.edgeDistance(landX, landZ) > 1.5 && rng.nextDouble() < chance) {
                jump = true;
                jumpCooldown = 10 + rng.nextInt(20);
            }
        }

        // ---- Clicking at a human rate, only when roughly aimed and close.
        clickTimer -= 1;
        double aimError = Math.abs(wrap((float) yawTo(self.x, self.z, t[0], t[2]) - yaw));
        boolean attack = false;
        if (dist < REACH + 0.6 && aimError < 25) {
            if (clickTimer <= 0) {
                attack = true;
                clickTimer += 20.0 / difficulty.cps() * (0.7 + 0.6 * rng.nextDouble());
                if (clickTimer < 0) clickTimer = 0;
            }
        } else if (clickTimer < 0) {
            clickTimer = 0;
        }

        out.forward = forward;
        out.strafe = strafe;
        out.sprint = forward > 0 && !wtap;
        out.jump = jump;
        out.attack = attack;
        out.yaw = yaw;
        out.pitch = pitch;
        return out;
    }

    private void pickStrafe(Body self, double[] t, double lx, double lz, double w) {
        // Where to stand so the player is between us and their nearest edge.
        double[] tOut = arena.nearestEdgeNormal(t[0], t[2]);
        double idealX = t[0] - tOut[0] * 2.7;
        double idealZ = t[2] - tOut[1] * 2.7;
        double side = (idealX - self.x) * lx + (idealZ - self.z) * lz;
        double edgeFocus = 0.35 + 0.45 * w + 0.3 * profile.edgeHabit();
        double targetEdge = arena.edgeDistance(t[0], t[2]);
        double preferred = profile.preferredLateral();
        if (targetEdge < 5 && Math.abs(side) > 0.3 && rng.nextDouble() < edgeFocus) {
            strafeDir = side > 0 ? 1 : -1;
        } else if (preferred != 0 && rng.nextDouble() < 0.5 * w) {
            strafeDir = preferred > 0 ? 1 : -1; // mirror the player's habit to stay in front of them
        } else {
            strafeDir = rng.nextBoolean() ? 1 : -1;
        }
        double run = profile.expectedStrafeRun();
        int base = run > 0 && rng.nextDouble() < w ? (int) Math.round(run) : 8 + rng.nextInt(15);
        base = (int) Math.round(base * style.strafeTimeMul);
        strafeTimer = Math.max(4, Math.min(40, base + rng.nextInt(5) - 2));
    }

    private void observe(Body self, Body target) {
        if (haveLast) {
            double vx = target.x - lastTx;
            double vz = target.z - lastTz;
            double dx = target.x - self.x;
            double dz = target.z - self.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 1e-6) {
                double lat = vx * (dz / d) + vz * (-dx / d);
                int dir = lat > 0.04 ? 1 : (lat < -0.04 ? -1 : 0);
                if (dir != 0) {
                    profile.recordLateralTick(dir);
                    if (dir == lateralDir) {
                        lateralRun++;
                    } else {
                        if (lateralDir != 0) profile.recordStrafeRun(lateralRun);
                        lateralDir = dir;
                        lateralRun = 1;
                    }
                }
            }
            boolean jumped = lastTOnGround && !target.onGround && target.y - lastTy > 0.05;
            profile.recordTick(jumped, arena.edgeDistance(target.x, target.z) < 2.5);
        }
        haveLast = true;
        lastTx = target.x;
        lastTy = target.y;
        lastTz = target.z;
        lastTOnGround = target.onGround;
    }

    /** Minecraft yaw that looks from (x, z) towards (tx, tz). */
    static double yawTo(double x, double z, double tx, double tz) {
        return Math.toDegrees(Math.atan2(tz - z, tx - x)) - 90.0;
    }

    static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float key(double v) {
        return v > 0.38 ? 1f : (v < -0.38 ? -1f : 0f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
