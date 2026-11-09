package com.kestrel.openmap;

import com.kestrel.sumo.Playstyle;
import com.kestrel.sumo.SumoDifficulty;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Decides the Open Map opponent's inputs every tick, the way a player would
 * press them: look direction, WASD, jump, sprint, left click, right click and
 * the hotbar slot. It only uses what a player can see (positions, movement,
 * the hurt flash, the blocks around), and sees it late by its reaction time.
 * The entity then plays the inputs through vanilla movement, combat, item and
 * block rules, so nothing here can exceed what a player can do.
 */
public final class PvpBrain {
    public enum Slot { SWORD, PLANKS, ROD, SNOWBALL }

    /** The current activity. */
    public enum Mode { CHASE, MELEE, RETREAT, ROD, SNOWBALL, TOWER, WALL }

    public static final double REACH = 3.0;
    /** Survival block reach from the eyes. */
    public static final double PLACE_REACH = 4.5;
    public static final double EYE = 1.62;

    /** What the opponent knows about itself. */
    public static final class Self {
        public double x, y, z, mx, my, mz;
        public float yaw, pitch;
        public boolean onGround, collided, sprinting;
        public float health;
        public float fallDistance;
        public int planks, snowballs;
        public boolean hasRod, hasSword;
        public boolean hookOut, hookHooked, hookStopped;
        public int hookAge;
        public Slot slot = Slot.SWORD;
    }

    /** What it sees of the player. */
    public static final class Target {
        public double x, y, z;
        public float yaw;
        public boolean onGround, sprinting, visible;
        public int hurtTime;

        Target copy() {
            Target t = new Target();
            t.x = x;
            t.y = y;
            t.z = z;
            t.yaw = yaw;
            t.onGround = onGround;
            t.sprinting = sprinting;
            t.visible = visible;
            t.hurtTime = hurtTime;
            return t;
        }
    }

    /** One tick of inputs. */
    public static final class Controls {
        public float yaw, pitch, forward, strafe;
        public boolean jump, sprint, attack, use;
        public Slot slot = Slot.SWORD;
        /** Place a plank at (px, py, pz) this tick (the entity checks the rules). */
        public boolean place;
        public int px, py, pz;
        public Mode mode = Mode.CHASE;
    }

    private final Random rng;
    private final Terrain terrain;
    private final PathFinder paths;
    private SumoDifficulty diff;
    private Playstyle style = Playstyle.BALANCED;
    private final PvpProfile profile;
    private final Controls out = new Controls();

    // Seeing late: the target as it was reactionTicks ago.
    private final ArrayDeque<Target> seen = new ArrayDeque<Target>();
    private double tvx, tvy, tvz;
    private double lastTx = Double.NaN, lastTy, lastTz;

    private Mode mode = Mode.CHASE;
    private int modeTicks;
    private int clickCooldown;
    private int wtapTicks;
    private int strafeDir = 1;
    private int strafeTimer;
    private int rodCooldown = 40;
    private int snowCooldown = 40;
    private int buildCooldown = 60;
    private int decideTimer;
    private int snowBurst;
    private int towerBase;
    private int towerLeft;
    private int wallLeft;
    private int wallX, wallZ;
    private int hookWait;
    private boolean rodCast;
    private int stuckTicks;
    private double progressX, progressZ;
    /** What it thinks the player's health is, from its own hits (players don't see health). */
    private double targetHealth = 20;
    private int regenTimer;

    // Path following.
    private List<PathFinder.Node> path = Collections.emptyList();
    private int pathIndex;
    private int replanTimer;
    private double planGoalX = Double.NaN, planGoalZ;

    // Per-match jitter, so its choices aren't the same every round.
    private double jitterRange;
    private double jitterRod;
    private double jitterSnow;
    private double jitterBuild;

    public PvpBrain(SumoDifficulty diff, PvpProfile profile, Terrain terrain, Random rng) {
        this.diff = diff;
        this.profile = profile;
        this.terrain = terrain;
        this.rng = rng;
        this.paths = new PathFinder(terrain, 1600, 48);
        resetRound();
    }

    public void setDifficulty(SumoDifficulty d) {
        diff = d;
    }

    public void setPlaystyle(Playstyle s) {
        style = s == null ? Playstyle.BALANCED : s;
    }

    public Mode mode() {
        return mode;
    }

    /** A new round: forget the old plan and pick this round's tendencies. */
    public void resetRound() {
        seen.clear();
        lastTx = Double.NaN;
        tvx = tvy = tvz = 0;
        mode = Mode.CHASE;
        modeTicks = 0;
        clickCooldown = 0;
        wtapTicks = 0;
        rodCooldown = 30 + rng.nextInt(40);
        snowCooldown = 30 + rng.nextInt(40);
        buildCooldown = 60;
        snowBurst = 0;
        towerLeft = 0;
        wallLeft = 0;
        rodCast = false;
        stuckTicks = 0;
        targetHealth = 20;
        regenTimer = 0;
        path = Collections.emptyList();
        pathIndex = 0;
        replanTimer = 0;
        planGoalX = Double.NaN;
        // Less predictable as it uses more of what it learned.
        double spread = 0.15 + 0.35 * diff.adaptWeight();
        jitterRange = (rng.nextDouble() - 0.5) * 0.4 * spread;
        jitterRod = 1 + (rng.nextDouble() - 0.5) * spread;
        jitterSnow = 1 + (rng.nextDouble() - 0.5) * spread;
        jitterBuild = 1 + (rng.nextDouble() - 0.5) * spread;
    }

    // ---- Feedback from the entity.

    /** It landed a sword hit for about {@code damage} (before the player's armor). */
    public void onHitLanded(boolean sprintHit, double damage) {
        targetHealth = Math.max(1, targetHealth - damage * 0.6); // iron armor takes ~40%
        if (sprintHit && rng.nextDouble() < diff.wtapChance()) wtapTicks = 2 + rng.nextInt(3);
    }

    /** It got hit by the player (sword). */
    public void onHitTaken() {
        if (mode == Mode.TOWER || mode == Mode.WALL) return;
        if (rng.nextDouble() < 0.3) strafeDir = -strafeDir;
    }

    /** The player ate the golden apple (visible: eating, then absorption hearts aren't visible but the bite is). */
    public void onTargetHealed() {
        targetHealth = Math.min(20, targetHealth + 8);
    }

    // ---- The decision.

    public Controls tick(Self me, Target now) {
        Target t = delayed(now);
        trackVelocity(t);
        regen();
        Controls c = out;
        c.place = false;
        c.attack = false;
        c.use = false;
        c.jump = false;
        c.sprint = false;
        c.forward = 0;
        c.strafe = 0;
        c.slot = me.hasSword ? Slot.SWORD : me.slot;
        if (clickCooldown > 0) clickCooldown--;
        if (rodCooldown > 0) rodCooldown--;
        if (snowCooldown > 0) snowCooldown--;
        if (buildCooldown > 0) buildCooldown--;
        modeTicks++;

        double dx = t.x - me.x;
        double dz = t.z - me.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dy = t.y - me.y;
        trackStuck(me);

        if (--decideTimer <= 0 || mode == Mode.CHASE || mode == Mode.MELEE) {
            if (decideTimer <= 0) decideTimer = 6 + rng.nextInt(5);
            choose(me, t, dist, dy);
        }
        switch (mode) {
            case TOWER:
                tower(me, t, c);
                break;
            case WALL:
                wall(me, t, c);
                break;
            case ROD:
                rod(me, t, dist, c);
                break;
            case SNOWBALL:
                snowball(me, t, dist, c);
                break;
            case RETREAT:
                retreat(me, t, dist, c);
                break;
            case MELEE:
                melee(me, t, dist, c);
                break;
            default:
                chase(me, t, dist, c);
                break;
        }
        c.mode = mode;
        return c;
    }

    private void setMode(Mode m) {
        if (m != mode) {
            mode = m;
            modeTicks = 0;
        }
    }

    private void choose(Self me, Target t, double dist, double dy) {
        // Multi-tick actions finish (or time out) on their own.
        if (mode == Mode.TOWER || mode == Mode.WALL || mode == Mode.ROD || mode == Mode.SNOWBALL) return;
        double adapt = diff.adaptWeight();
        boolean low = me.health <= retreatHealth() && targetHealth > me.health + 2;
        boolean recovered = me.health >= 14 || targetHealth <= me.health;

        // Stuck against a wall, or the player is up high: build up.
        if (me.planks >= 2 && buildCooldown == 0 && me.onGround
                && (stuckTicks > 20 || (dy >= 2.5 && dist < 6 && t.visible))) {
            startTower(me, dy >= 2.5 ? (int) Math.min(3, Math.ceil(dy)) : 1);
            return;
        }
        if (mode == Mode.RETREAT && !recovered && dist < 18) {
            // Under pressure: block them off or get up out of reach.
            if (me.planks >= 2 && buildCooldown == 0 && dist < 5.5 && rng.nextDouble() < 0.5 * buildMul() * jitterBuild) {
                if (rng.nextBoolean() && me.onGround && dist > 2.2) startWall(me, t);
                else if (me.onGround) startTower(me, 2);
                return;
            }
            if (me.snowballs > 0 && snowCooldown == 0 && dist > 5 && t.visible) {
                startSnowball(2);
                return;
            }
            return; // keep retreating
        }
        if (low && dist < 10) {
            setMode(Mode.RETREAT);
            return;
        }
        // Mid range: rod or snowballs to open them up.
        if (t.visible && me.hasRod && rodCooldown == 0 && dist > 3.6 && dist < 12
                && rng.nextDouble() < rodChance(adapt) * jitterRod) {
            startRod();
            return;
        }
        boolean targetBuilding = dy > 1.2 && !t.onGround;
        if (t.visible && me.snowballs > 0 && snowCooldown == 0 && dist > 6 && dist < 24
                && rng.nextDouble() < snowChance(adapt, targetBuilding) * jitterSnow) {
            startSnowball(1 + rng.nextInt(3));
            return;
        }
        setMode(t.visible && dist < 7 ? Mode.MELEE : Mode.CHASE);
    }

    // ---- Chasing across the map.

    private void chase(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.SWORD;
        followPath(me, t, c);
        if (t.visible && dist < 9) aimAt(me, t, c, true);
    }

    private void followPath(Self me, Target t, Controls c) {
        int fx = floor(me.x);
        int fy = (int) Math.round(me.y);
        int fz = floor(me.z);
        boolean moved = Double.isNaN(planGoalX) || Math.abs(planGoalX - t.x) + Math.abs(planGoalZ - t.z) > 3;
        if (--replanTimer <= 0 || moved || pathIndex >= path.size()) {
            replanTimer = 10;
            PathFinder.Node start = paths.ground(fx, fy, fz);
            PathFinder.Node goal = paths.ground(floor(t.x), (int) Math.round(t.y), floor(t.z));
            if (goal == null) goal = new PathFinder.Node(floor(t.x), (int) Math.round(t.y), floor(t.z));
            path = start == null ? Collections.<PathFinder.Node>emptyList() : paths.find(start, goal, 1.5);
            pathIndex = 0;
            planGoalX = t.x;
            planGoalZ = t.z;
        }
        double gx = t.x;
        double gz = t.z;
        int nextY = fy;
        while (pathIndex < path.size()) {
            PathFinder.Node n = path.get(pathIndex);
            double ex = n.x + 0.5 - me.x;
            double ez = n.z + 0.5 - me.z;
            if (ex * ex + ez * ez < 0.36 && Math.abs(n.y - me.y) < 1.2) {
                pathIndex++;
                continue;
            }
            gx = n.x + 0.5;
            gz = n.z + 0.5;
            nextY = n.y;
            break;
        }
        float moveYaw = (float) yawTo(me.x, me.z, gx, gz);
        c.yaw = turnToward(me.yaw, moveYaw, diff.maxTurnDeg());
        c.pitch = approach(me.pitch, 5f, 10f);
        steer(c, moveYaw, 1f);
        c.sprint = true;
        if (me.onGround && (nextY > fy || (me.collided && stuckTicks > 3))) c.jump = true;
    }

    // ---- Close combat.

    private void melee(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.SWORD;
        if (me.slot != Slot.SWORD) {
            aimAt(me, t, c, true);
            return; // switching this tick
        }
        aimAt(me, t, c, true);
        double range = preferredRange();
        float toTarget = (float) yawTo(me.x, me.z, t.x, t.z);
        float forward;
        if (wtapTicks > 0) {
            wtapTicks--;
            forward = 0f; // release W: the next hit is a sprint hit again
        } else if (dist > range + 0.5) {
            forward = 1f;
        } else if (dist < range - 0.7) {
            forward = -0.4f;
        } else {
            forward = 0.8f + (float) rng.nextDouble() * 0.2f;
        }
        // Don't walk straight into a sprinting player facing us: side-step first.
        boolean theyRush = t.sprinting && facing(t, me) < 25 && dist > 3.1 && dist < 4.6;
        if (--strafeTimer <= 0) {
            strafeTimer = (int) (strafeTicks() * (0.6 + rng.nextDouble() * 0.8));
            double sameSide = 0.5 + (profile.strafeLeft - 0.5) * diff.adaptWeight();
            strafeDir = rng.nextDouble() < sameSide ? 1 : -1;
        }
        float strafe = strafeDir * (theyRush ? 1f : 0.7f);
        // Forward/strafe are relative to the look direction; while still turning onto
        // the target, move toward it rather than sideways.
        if (Math.abs(wrap(c.yaw - toTarget)) > 60) {
            steer(c, toTarget, Math.abs(forward));
            strafe = c.strafe;
            forward = c.forward;
        }
        c.forward = forward;
        c.strafe = strafe;
        c.sprint = forward >= 0.8f;
        // Jump for a falling critical hit when coming into range, unless they jump a lot
        // (then hit them on the ground as they land).
        double critChance = 0.25 + 0.45 * diff.skill() - 0.15 * diff.adaptWeight() * Math.max(0, profile.jumpRate - 1);
        if (me.onGround && dist > 2.4 && dist < 3.6 && t.hurtTime <= 2 && rng.nextDouble() < critChance * 0.25) c.jump = true;
        if (me.collided && me.onGround) c.jump = true;
        // Click: within reach, aimed, and their hurt flash is over (a hit lands again).
        boolean falling = !me.onGround && me.my < 0 && me.fallDistance > 0;
        int window = diff.skill() > 0.6 ? 1 : 2;
        if (dist <= REACH + 0.35 && clickCooldown == 0 && aimError(me, t) < 30
                && (t.hurtTime <= window || falling)) {
            c.attack = true;
            clickCooldown = clickGap();
        } else if (dist <= REACH + 1.5 && clickCooldown == 0 && rng.nextDouble() < 0.3) {
            // Players click a little early too; a miss just swings.
            c.attack = true;
            clickCooldown = clickGap();
        }
    }

    private void retreat(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.SWORD;
        // Run away from them, along the ground, glancing back to fight if they catch up.
        if (dist < 2.8) {
            melee(me, t, dist, c);
            return;
        }
        double ax = me.x - (t.x - me.x) * 4;
        double az = me.z - (t.z - me.z) * 4;
        float away = (float) yawTo(me.x, me.z, ax, az);
        c.yaw = turnToward(me.yaw, away, diff.maxTurnDeg());
        c.pitch = approach(me.pitch, 0f, 10f);
        steer(c, away, 1f);
        c.sprint = true;
        if (me.onGround && me.collided) c.jump = true;
        if (me.health >= 14 || dist > 16 || targetHealth <= me.health) setMode(Mode.CHASE);
    }

    // ---- Fishing rod.

    private void startRod() {
        setMode(Mode.ROD);
        rodCast = false;
        hookWait = 0;
    }

    private void rod(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.ROD;
        if (!rodCast) {
            Ballistics.Shot shot = aimProjectile(me, t, Ballistics.Kind.HOOK, c);
            if (me.slot == Slot.ROD && shot != null && Math.abs(wrap(c.yaw - me.yaw)) < 6 && !me.hookOut) {
                c.use = true; // cast
                rodCast = true;
            }
            if (modeTicks > 16) endRod(c); // couldn't line it up
            moveWhileUsing(me, t, dist, c);
            return;
        }
        aimAt(me, t, c, false);
        moveWhileUsing(me, t, dist, c);
        if (!me.hookOut) {
            endRod(c);
            return;
        }
        if (me.hookHooked) {
            // Hooked: let the knockback land, then reel in and rush the opening.
            if (++hookWait >= 2 + rng.nextInt(3)) {
                c.use = true;
                endRod(c);
                wtapTicks = 0;
            }
        } else if (me.hookStopped || me.hookAge > 18) {
            c.use = true; // missed: reel in
            endRod(c);
        }
    }

    private void endRod(Controls c) {
        rodCooldown = (int) (40 + rng.nextInt(50) / Math.max(0.5, style == Playstyle.AGGRESSIVE ? 1.3 : 1.0));
        rodCast = false;
        setMode(Mode.CHASE);
        c.slot = Slot.SWORD;
    }

    // ---- Snowballs.

    private void startSnowball(int count) {
        setMode(Mode.SNOWBALL);
        snowBurst = count;
    }

    private void snowball(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.SNOWBALL;
        moveWhileUsing(me, t, dist, c);
        if (me.snowballs <= 0 || snowBurst <= 0 || modeTicks > 40 || dist < 3.2) {
            snowCooldown = 30 + rng.nextInt(50);
            setMode(Mode.CHASE);
            c.slot = Slot.SWORD;
            return;
        }
        Ballistics.Shot shot = aimProjectile(me, t, Ballistics.Kind.SNOWBALL, c);
        if (me.slot == Slot.SNOWBALL && shot != null && clickCooldown == 0 && Math.abs(wrap(c.yaw - me.yaw)) < 5) {
            c.use = true;
            snowBurst--;
            clickCooldown = 3 + rng.nextInt(3); // a fast human right-click rate
        }
    }

    /** Keep moving sensibly while using an item: keep the distance, strafe a little. */
    private void moveWhileUsing(Self me, Target t, double dist, Controls c) {
        float toTarget = (float) yawTo(me.x, me.z, t.x, t.z);
        float f = dist > 9 ? 1f : (dist < 4 ? -0.5f : 0.3f);
        c.forward = f * (float) Math.cos(Math.toRadians(wrap(toTarget - c.yaw)));
        c.strafe = strafeDir * 0.5f;
        c.sprint = false;
        if (me.onGround && me.collided) c.jump = true;
    }

    // ---- Building with planks.

    private void startTower(Self me, int height) {
        setMode(Mode.TOWER);
        towerBase = (int) Math.floor(me.y + 0.01);
        towerLeft = Math.max(1, height);
        buildCooldown = 100;
        stuckTicks = 0;
    }

    /** Pillar up: look down, jump, place a plank under your feet at the top of the jump. */
    private void tower(Self me, Target t, Controls c) {
        c.slot = Slot.PLANKS;
        c.yaw = me.yaw;
        c.pitch = approach(me.pitch, 90f, diff.maxTurnDeg());
        int bx = floor(me.x);
        int bz = floor(me.z);
        boolean headroom = terrain.open(bx, towerBase + 2, bz) && terrain.open(bx, towerBase + 3, bz);
        if (me.planks <= 0 || towerLeft <= 0 || modeTicks > 60 || !headroom) {
            setMode(Mode.CHASE);
            c.slot = Slot.SWORD;
            return;
        }
        if (me.slot != Slot.PLANKS || me.pitch < 70) return;
        if (me.onGround) {
            if (me.y >= towerBase + 0.99) {
                // Landed on the new block.
                towerBase = (int) Math.floor(me.y + 0.01);
                towerLeft--;
                if (towerLeft <= 0) {
                    setMode(t.visible && PathFinder.horizontal(new PathFinder.Node(bx, 0, bz),
                            new PathFinder.Node(floor(t.x), 0, floor(t.z))) < 6 ? Mode.MELEE : Mode.CHASE);
                    c.slot = Slot.SWORD;
                    return;
                }
            }
            c.jump = true;
        } else if (me.y >= towerBase + 1.0 && terrain.open(bx, towerBase, bz)) {
            c.place = true;
            c.px = bx;
            c.py = towerBase;
            c.pz = bz;
        }
    }

    private void startWall(Self me, Target t) {
        setMode(Mode.WALL);
        double ang = Math.atan2(t.z - me.z, t.x - me.x);
        wallX = floor(me.x) + (int) Math.round(Math.cos(ang));
        wallZ = floor(me.z) + (int) Math.round(Math.sin(ang));
        towerBase = (int) Math.floor(me.y + 0.01);
        wallLeft = 2;
        buildCooldown = 80;
    }

    /** Two planks stacked between it and the player: a quick barrier. */
    private void wall(Self me, Target t, Controls c) {
        c.slot = Slot.PLANKS;
        if (me.planks <= 0 || wallLeft <= 0 || modeTicks > 30) {
            setMode(Mode.RETREAT);
            c.slot = Slot.SWORD;
            return;
        }
        int y = towerBase + (2 - wallLeft);
        if (!terrain.open(wallX, y, wallZ)) {
            wallLeft--; // already blocked there
            return;
        }
        // Look at the spot (the target of a right click).
        double ex = wallX + 0.5 - me.x;
        double ey = y + 0.5 - (me.y + EYE);
        double ez = wallZ + 0.5 - me.z;
        float yaw = (float) yawTo(me.x, me.z, wallX + 0.5, wallZ + 0.5);
        float pitch = (float) -Math.toDegrees(Math.atan2(ey, Math.sqrt(ex * ex + ez * ez)));
        c.yaw = turnToward(me.yaw, yaw, diff.maxTurnDeg());
        c.pitch = approach(me.pitch, pitch, diff.maxTurnDeg());
        c.forward = 0;
        if (me.slot == Slot.PLANKS && Math.abs(wrap(c.yaw - yaw)) < 15 && Math.abs(c.pitch - pitch) < 15) {
            c.place = true;
            c.px = wallX;
            c.py = y;
            c.pz = wallZ;
            wallLeft--;
        }
    }

    /** The entity reports whether the last requested block went down. */
    public void onPlaceResult(boolean placed) {
        if (!placed && mode == Mode.WALL) wallLeft = 0;
    }

    // ---- Aiming.

    /** Aim at the target's body, leading its movement a little (reacting late, with noise). */
    private void aimAt(Self me, Target t, Controls c, boolean body) {
        double lead = diff.leadTicks();
        double sideLead = (profile.strafeLeft - 0.5) * diff.adaptWeight() * 0.3;
        double px = t.x + tvx * lead;
        double pz = t.z + tvz * lead;
        double py = t.y + (body ? 1.2 : 1.0) + tvy * lead;
        double noise = diff.aimNoiseDeg() * (rng.nextDouble() - 0.5) * 0.6;
        float yaw = (float) (yawTo(me.x, me.z, px, pz) + noise + sideLead);
        double ddx = px - me.x;
        double ddz = pz - me.z;
        float pitch = (float) -Math.toDegrees(Math.atan2(py - (me.y + EYE), Math.sqrt(ddx * ddx + ddz * ddz)));
        c.yaw = turnToward(me.yaw, yaw, diff.maxTurnDeg());
        c.pitch = approach(me.pitch, pitch, diff.maxTurnDeg());
    }

    /** Aim a thrown item at where the target will be when it arrives. */
    private Ballistics.Shot aimProjectile(Self me, Target t, Ballistics.Kind kind, Controls c) {
        double px = t.x;
        double pz = t.z;
        Ballistics.Shot shot = null;
        for (int i = 0; i < 3; i++) {
            double ddx = px - me.x;
            double ddz = pz - me.z;
            double d = Math.sqrt(ddx * ddx + ddz * ddz);
            shot = Ballistics.solve(kind, d, (t.y + 1.0) - (me.y + EYE - 0.1), 60);
            if (shot == null) break;
            double lead = Math.min(shot.ticks, 20) * (0.5 + 0.5 * diff.skill());
            px = t.x + tvx * lead;
            pz = t.z + tvz * lead;
        }
        float yaw = (float) yawTo(me.x, me.z, px, pz);
        float pitch = shot == null ? me.pitch : shot.pitch;
        double noise = diff.aimNoiseDeg() * (rng.nextDouble() - 0.5) * 0.4;
        c.yaw = turnToward(me.yaw, (float) (yaw + noise), diff.maxTurnDeg());
        c.pitch = approach(me.pitch, pitch, diff.maxTurnDeg());
        return shot;
    }

    private double aimError(Self me, Target t) {
        return Math.abs(wrap((float) yawTo(me.x, me.z, t.x, t.z) - me.yaw));
    }

    // ---- Tendencies (style, learning, skill).

    private double preferredRange() {
        double r = 2.85 + jitterRange;
        switch (style) {
            case AGGRESSIVE:
                r -= 0.25;
                break;
            case DEFENSIVE:
                r += 0.2;
                break;
            default:
                break;
        }
        // Against rushers, hold a bit further and let them run into the hit.
        r += 0.25 * diff.adaptWeight() * (profile.rush - 0.5);
        return Math.max(2.3, Math.min(3.1, r));
    }

    private int strafeTicks() {
        return style == Playstyle.STRAFER ? 12 : (style == Playstyle.DEFENSIVE ? 30 : 22);
    }

    private double retreatHealth() {
        return style == Playstyle.AGGRESSIVE ? 4 : (style == Playstyle.DEFENSIVE ? 9 : 7);
    }

    private double buildMul() {
        return style == Playstyle.DEFENSIVE ? 1.4 : (style == Playstyle.AGGRESSIVE ? 0.6 : 1.0);
    }

    private double rodChance(double adapt) {
        double c = 0.18;
        if (style == Playstyle.AGGRESSIVE) c *= 1.3;
        // Players who hold back or run get pulled in more.
        c *= 1 + adapt * (0.5 - profile.rush + profile.retreat * 0.5);
        return c;
    }

    private double snowChance(double adapt, boolean targetBuilding) {
        double c = 0.12;
        if (style == Playstyle.DEFENSIVE) c *= 1.4;
        if (targetBuilding) c *= 2.5;
        // Builders get interrupted more.
        c *= 1 + adapt * Math.min(1.5, profile.buildRate / 10);
        return c;
    }

    private int clickGap() {
        double cps = diff.cps() * (0.85 + rng.nextDouble() * 0.3);
        return Math.max(1, (int) Math.round(20 / cps));
    }

    // ---- Helpers.

    private Target delayed(Target now) {
        seen.addLast(now.copy());
        int delay = diff.reactionTicks();
        while (seen.size() > delay + 1) seen.removeFirst();
        return seen.peekFirst();
    }

    private void trackVelocity(Target t) {
        if (!Double.isNaN(lastTx)) {
            tvx = tvx * 0.5 + (t.x - lastTx) * 0.5;
            tvy = tvy * 0.5 + (t.y - lastTy) * 0.5;
            tvz = tvz * 0.5 + (t.z - lastTz) * 0.5;
        }
        lastTx = t.x;
        lastTy = t.y;
        lastTz = t.z;
    }

    private void regen() {
        // Players heal half a heart every 4 seconds with full food; assume they do.
        if (++regenTimer >= 80) {
            regenTimer = 0;
            targetHealth = Math.min(20, targetHealth + 1);
        }
    }

    private void trackStuck(Self me) {
        double moved = Math.abs(me.x - progressX) + Math.abs(me.z - progressZ);
        if (moved > 0.3) {
            progressX = me.x;
            progressZ = me.z;
            stuckTicks = 0;
        } else if (mode == Mode.CHASE || mode == Mode.RETREAT) {
            stuckTicks++;
        }
    }

    /** Set forward/strafe so the body moves toward {@code moveYaw} while looking at {@code c.yaw}. */
    private static void steer(Controls c, float moveYaw, float amount) {
        double rel = Math.toRadians(wrap(moveYaw - c.yaw));
        c.forward = (float) (Math.cos(rel) * amount);
        c.strafe = (float) (-Math.sin(rel) * amount);
    }

    /** How directly {@code t} looks at {@code me} (degrees). */
    private static double facing(Target t, Self me) {
        return Math.abs(wrap((float) yawTo(t.x, t.z, me.x, me.z) - t.yaw));
    }

    public static double yawTo(double fx, double fz, double tx, double tz) {
        return Math.toDegrees(Math.atan2(tz - fz, tx - fx)) - 90.0;
    }

    static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static float turnToward(float from, float to, float max) {
        float d = wrap(to - from);
        if (d > max) d = max;
        if (d < -max) d = -max;
        return from + d;
    }

    private static float approach(float from, float to, float max) {
        float d = to - from;
        if (d > max) d = max;
        if (d < -max) d = -max;
        return Math.max(-90f, Math.min(90f, from + d));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
