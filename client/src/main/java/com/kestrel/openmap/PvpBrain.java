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
    public enum Mode { CHASE, MELEE, RETREAT, ROD, SNOWBALL, TOWER, WALL, STAIRS, BRIDGE }

    public static final double REACH = 3.0;
    /** Survival block reach from the eyes. */
    public static final double PLACE_REACH = 4.5;
    public static final double EYE = 1.62;
    /**
     * The Open Map opponent plays a little below the difficulty it is set to
     * (players found it too sharp): it spaces, dodges and strafes less
     * perfectly and makes the odd mistake. How quickly it perceives, decides,
     * turns and moves comes from {@link PvpTempo}.
     */
    static final double EASE = 0.88;

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
        /** Which opponent this is (entity id); a different one is a new engagement. */
        public int id;

        Target copy() {
            Target t = new Target();
            t.id = id;
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
    /** How quickly it perceives, decides, turns and moves at this difficulty. */
    private PvpTempo tempo;
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
    /** Wall cells still to place: x, y, z triples. */
    private final int[] wallCells = new int[12];
    private int wallCount;
    private boolean retreatAfterWall;
    // First contact: a player who just came into reach isn't hit on that same tick. It
    // notices, reacts, then swings; once it's fighting there are no extra pauses.
    /** Fighting this opponent now: clicks follow the usual hit timing. */
    private boolean engaged;
    /** Ticks left before the first swing of a new engagement; -1 when not counting. */
    private int engageDelay = -1;
    /** Ticks the opponent has been out of reach (perceived). */
    private int outOfReachTicks;
    /** Tick count when it last stopped fighting (for re-engaging a known opponent). */
    private int disengagedAt = -1000;
    private int engagedTargetId = Integer.MIN_VALUE;
    private int clock;
    /** Player hits taken since its own last hit (being comboed). */
    private int hitsInRow;
    /** Tick count of the last player hit it took. */
    private int lastHitTaken = -1000;
    /** Hit from range (a snowball or the rod): take cover when it can. */
    private boolean coverWanted;
    private int stairBackoff;
    private double lastStepY;
    private int placeCooldown;
    private int wallIndex;
    private int stairsLeft;
    private int stairDX, stairDZ;
    private int bridgeLeft;
    private boolean snowAfterTower;
    // Reacting.
    private int recoverTicks;
    private int dodgeTicks;
    private int dodgeCooldown;
    private double dodgeAwayX, dodgeAwayZ;
    private double lastMeX, lastMeY, lastMeZ;
    private float lastMeYaw;
    private double lastDist = 99;
    // Imperfections.
    private int mistakeTimer;
    private int mistakeTicks;
    private int mistakeKind;
    private double rangeDrift;
    private double sideBias = 0.5;
    private boolean sidestepRush;
    private int hookWait;
    private boolean rodCast;
    private int stuckTicks;
    private double progressX, progressZ;
    /** What it thinks the player's health is, from its own hits (players don't see health). */
    private double targetHealth = 20;
    private int regenTimer;

    // Responsiveness (see PvpTempo).
    /** Current perception lag in ticks; drifts a tick at a time around the difficulty's base. */
    private int perceptionDelay = 1;
    /** Ticks since the plan was last reconsidered. */
    private int decisionSpan = 1;
    /** Turn speed (degrees per tick) at the start of this tick, and after it. */
    private float yawVelStart;
    private float yawVel;
    private boolean turnedThisTick;
    /** Last tick's movement keys (they change only so fast). */
    private float lastForward;
    private float lastStrafe;
    /** Keys released on purpose this tick (a W-tap): not smoothed. */
    private boolean keyRelease;

    // Defensive play when it is losing (0 = normal, 1 = fully defensive).
    private double defense;
    private int burstTicks;
    private int burstCooldown;
    private float burstYaw;

    // The arena: the world border. NaN when unknown.
    private double arenaMinX = Double.NaN, arenaMinZ, arenaMaxX, arenaMaxZ;
    /** Distance from the border within which it starts steering back inward. */
    static final double EDGE_MARGIN = 6.0;

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
        this.diff = eased(diff);
        this.tempo = new PvpTempo(diff.skill());
        this.profile = profile;
        this.terrain = terrain;
        this.rng = rng;
        this.paths = new PathFinder(terrain, 1600, 48);
        resetRound();
    }

    public void setDifficulty(SumoDifficulty d) {
        diff = eased(d);
        tempo = new PvpTempo(d.skill());
        perceptionDelay = (int) Math.round(tempo.perceptionTicks());
    }

    /** The arena's edges (the world border); it keeps itself inside them. */
    public void setArena(double minX, double minZ, double maxX, double maxZ) {
        arenaMinX = minX;
        arenaMinZ = minZ;
        arenaMaxX = maxX;
        arenaMaxZ = maxZ;
    }

    /** 0 = playing normally, 1 = fully defensive. */
    double defense() {
        return defense;
    }

    private static SumoDifficulty eased(SumoDifficulty d) {
        return new SumoDifficulty(d.skill() * EASE);
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
        buildCooldown = 30;
        snowBurst = 0;
        towerLeft = 0;
        wallCount = 0;
        wallIndex = 0;
        stairsLeft = 0;
        bridgeLeft = 0;
        snowAfterTower = false;
        recoverTicks = 0;
        engaged = false;
        engageDelay = -1;
        outOfReachTicks = 0;
        disengagedAt = -1000;
        hitsInRow = 0;
        lastHitTaken = -1000;
        coverWanted = false;
        dodgeTicks = 0;
        dodgeCooldown = 0;
        mistakeTimer = 60 + rng.nextInt(80);
        mistakeTicks = 0;
        rangeDrift = 0;
        // A habit for the round (one side more often), which a player can read and use.
        sideBias = rng.nextBoolean() ? 0.7 : 0.3;
        sidestepRush = rng.nextDouble() < 0.45 + 0.35 * diff.skill();
        rodCast = false;
        stuckTicks = 0;
        targetHealth = 20;
        regenTimer = 0;
        perceptionDelay = (int) Math.round(tempo.perceptionTicks());
        decisionSpan = 1;
        decideTimer = 0;
        yawVel = yawVelStart = 0;
        lastForward = lastStrafe = 0;
        defense = 0;
        burstTicks = 0;
        burstCooldown = 0;
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
        targetHealth = Math.max(1, targetHealth - damage * 0.2); // full diamond armor takes 80% (20 points x 4%)
        hitsInRow = 0;
        if (sprintHit && rng.nextDouble() < diff.wtapChance()) wtapTicks = 2 + rng.nextInt(3);
    }

    /** It got hit by the player (sword). */
    public void onHitTaken() {
        // React now rather than at the next decision: re-plan, and stop a slow item use up close.
        recoverTicks = 6;
        decideTimer = Math.min(decideTimer, tempo.vary(tempo.hitReactTicks(), rng, 0));
        hitsInRow++;
        lastHitTaken = clock;
        if (lastDist > 6) coverWanted = true;
        replanTimer = 0;
        if ((mode == Mode.SNOWBALL || (mode == Mode.ROD && !rodCast)) && lastDist < 5) setMode(Mode.CHASE);
        if (mode == Mode.TOWER || mode == Mode.WALL || mode == Mode.STAIRS || mode == Mode.BRIDGE) return;
        if (rng.nextDouble() < 0.3) strafeDir = -strafeDir;
    }

    /**
     * A projectile the player threw (snowball or rod hook), as it flies. If it
     * is heading for the opponent, it may side-step it (not always, and late
     * by its reaction time, like a player).
     */
    public void incoming(double px, double py, double pz, double vx, double vy, double vz) {
        if (dodgeTicks > 0 || dodgeCooldown > 0) return;
        double rx = lastMeX - px;
        double rz = lastMeZ - pz;
        double v2 = vx * vx + vz * vz;
        if (v2 < 0.04) return;
        double tHit = (rx * vx + rz * vz) / v2; // ticks to the closest point
        if (tHit < 1 || tHit > 18) return;
        double cx = px + vx * tHit - lastMeX;
        double cz = pz + vz * tHit - lastMeZ;
        if (cx * cx + cz * cz > 1.2 * 1.2) return; // passes wide
        dodgeCooldown = 12;
        if (rng.nextDouble() > 0.2 + 0.4 * diff.skill()) return; // didn't see it in time
        // Step away from the line it travels on.
        double ax = -cx;
        double az = -cz;
        if (ax * ax + az * az < 0.01) {
            double s = rng.nextBoolean() ? 1 : -1;
            ax = -vz * s;
            az = vx * s;
        }
        dodgeAwayX = ax;
        dodgeAwayZ = az;
        // Reaction delay before the step, then a few ticks of it.
        dodgeTicks = Math.min((int) tHit, reaction() + tempo.vary(tempo.dodgeReactTicks(), rng, 1));
    }

    /** The player ate the golden apple (visible: eating, then absorption hearts aren't visible but the bite is). */
    public void onTargetHealed() {
        targetHealth = Math.min(20, targetHealth + 8);
    }

    // ---- The decision.

    public Controls tick(Self me, Target now) {
        lastMeX = me.x;
        lastMeY = me.y;
        lastMeZ = me.z;
        lastMeYaw = me.yaw;
        tempo.drift(rng);
        updatePerception();
        yawVelStart = turnedThisTick ? yawVel : 0f;
        yawVel = 0f;
        turnedThisTick = false;
        keyRelease = false;
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
        if (dodgeCooldown > 0) dodgeCooldown--;
        if (placeCooldown > 0) placeCooldown--;
        if (burstCooldown > 0) burstCooldown--;
        modeTicks++;
        updateImperfections();

        double dx = t.x - me.x;
        double dz = t.z - me.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dy = t.y - me.y;
        lastDist = dist;
        trackStuck(me);
        updateEngagement(t, dist);
        updateDefense(me, t, dist);

        if (clutch(me, c)) {
            remember(c);
            c.mode = mode;
            return c;
        }
        // The plan is reconsidered at the pace of its difficulty. Someone right in front of it
        // while it is running or chasing is a reaction (it has already seen them late), not a plan.
        boolean threat = (mode == Mode.CHASE || mode == Mode.RETREAT) && t.visible && dist < REACH + 1.2;
        if (--decideTimer <= 0 || threat) {
            if (decideTimer <= 0) decideTimer = tempo.vary(tempo.decisionTicks(), rng, 1);
            choose(me, t, dist, dy);
            decisionSpan = 0;
        }
        decisionSpan++;
        switch (mode) {
            case TOWER:
                tower(me, t, c);
                break;
            case WALL:
                wall(me, t, c);
                break;
            case STAIRS:
                stairs(me, t, c);
                break;
            case BRIDGE:
                bridge(me, t, c);
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
        applyDodge(c);
        if (!building()) keepInside(me, c);
        smoothInputs(c);
        if (recoverTicks > 0) recoverTicks--;
        c.mode = mode;
        return c;
    }

    /** Perception lag drifts one tick at a time toward the difficulty's base (scaled by focus). */
    private void updatePerception() {
        if (clock % 8 != 0) return;
        int want = (int) Math.round(tempo.perceptionTicks() * tempo.focus() + (rng.nextDouble() - 0.5) * 0.8);
        want = Math.max(1, Math.min(10, want));
        if (want > perceptionDelay) perceptionDelay++;
        else if (want < perceptionDelay) perceptionDelay--;
    }

    /**
     * Movement keys change only so fast: a slower opponent eases into a new
     * direction (a strafe reversal takes a few ticks on Easy, one on Expert).
     * Deliberate key releases and building moves are exact.
     */
    private void smoothInputs(Controls c) {
        if (!building() && !keyRelease) {
            float r = tempo.inputRate();
            c.forward = lastForward + clampf(c.forward - lastForward, r);
            c.strafe = lastStrafe + clampf(c.strafe - lastStrafe, r);
            if (c.forward < 0.8f) c.sprint = false;
        }
        remember(c);
    }

    private void remember(Controls c) {
        lastForward = c.forward;
        lastStrafe = c.strafe;
    }

    private static float clampf(float v, float max) {
        return v > max ? max : (v < -max ? -max : v);
    }

    /**
     * How defensively to play, from what it can tell: its own health against its
     * estimate of the player's, a run of hits taken, and a player rushing it. It
     * moves toward that at the pace of its difficulty, so it eases into and back
     * out of defending rather than flipping.
     */
    private void updateDefense(Self me, Target t, double dist) {
        if (hitsInRow > 0 && clock - lastHitTaken > 40) hitsInRow = 0; // that combo is over
        double low = retreatHealth();
        double want = (low + 6 - me.health) / 6.0; // starts 6 HP above the style's threshold, full at it
        want = Math.max(0, Math.min(1, want));
        double gap = targetHealth - me.health;
        if (gap <= -2) want *= 0.35; // winning the trade: keep pressing
        else if (gap < 2) want *= 0.75;
        if (hitsInRow >= 2) want += 0.15 * Math.min(3, hitsInRow - 1);
        if (t.visible && t.sprinting && dist < 6 && facing(t, me) < 30) want += 0.1;
        want = Math.max(0, Math.min(1, want));
        double rate = tempo.styleShiftRate();
        defense += Math.max(-rate, Math.min(rate, want - defense));
    }

    private boolean building() {
        return mode == Mode.TOWER || mode == Mode.WALL || mode == Mode.STAIRS || mode == Mode.BRIDGE;
    }

    /** Side-step a projectile: move away from its line, whatever else it's doing. */
    private void applyDodge(Controls c) {
        if (dodgeTicks <= 0) return;
        dodgeTicks--;
        if (building() || dodgeTicks > 4) return; // still reacting (or busy placing)
        // Strafe direction in world space for this look direction (vanilla moveFlying).
        double yr = Math.toRadians(c.yaw);
        double left = dodgeAwayX * Math.cos(yr) + dodgeAwayZ * Math.sin(yr);
        c.strafe = left >= 0 ? 1f : -1f;
    }

    /** Mistake windows now and then, and a slowly wandering preferred distance. */
    private void updateImperfections() {
        rangeDrift += (rng.nextDouble() - 0.5) * 0.06;
        rangeDrift = Math.max(-0.3, Math.min(0.3, rangeDrift));
        if (mistakeTicks > 0) {
            mistakeTicks--;
            return;
        }
        if (--mistakeTimer <= 0) {
            mistakeTimer = 70 + rng.nextInt(80);
            if (rng.nextDouble() < 0.65 - 0.3 * diff.skill()) {
                mistakeTicks = 6 + rng.nextInt(8);
                mistakeKind = rng.nextInt(3); // 0 overcommit, 1 sloppy aim, 2 slow to turn
            }
        }
    }

    private boolean mistake(int kind) {
        return mistakeTicks > 0 && mistakeKind == kind;
    }

    private float turnRate() {
        return mistake(2) ? tempo.maxTurnDeg() * 0.45f : tempo.maxTurnDeg();
    }

    /**
     * Turn toward {@code to}: no faster than {@link #turnRate()}, and the turn
     * speed builds up and slows down at the difficulty's pace (a slow opponent
     * swings round sluggishly; a fast one flicks). Calling it again in the same
     * tick replaces that tick's turn rather than adding to it.
     */
    private float turnYaw(float from, float to) {
        float d = wrap(to - from);
        float max = turnRate();
        float want = d > max ? max : (d < -max ? -max : d);
        float accel = tempo.turnAccelDeg();
        float v = yawVelStart + clampf(want - yawVelStart, accel);
        if ((d >= 0 && v > d) || (d <= 0 && v < d)) v = d; // don't overshoot
        if (v > max) v = max;
        if (v < -max) v = -max;
        yawVel = v;
        turnedThisTick = true;
        return from + v;
    }

    /**
     * Knocked off something high: put a plank under the feet on the way down,
     * if there's a face to place it against (as a player "clutches").
     */
    private boolean clutch(Self me, Controls c) {
        if (me.onGround || me.my > -0.15 || me.planks <= 0 || building()) return false;
        int bx = floor(me.x);
        int bz = floor(me.z);
        int fy = floor(me.y);
        int drop = 0;
        while (drop < 6 && terrain.open(bx, fy - 1 - drop, bz)) drop++;
        if (drop < 4) return false; // a short fall: just land
        int y = fy - 1;
        boolean support = false;
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (int[] f : faces) {
            if (terrain.solid(bx + f[0], y + f[1], bz + f[2])) {
                support = true;
                break;
            }
        }
        if (!support) return false;
        c.slot = Slot.PLANKS;
        c.yaw = me.yaw;
        c.pitch = approach(me.pitch, 90f, turnRate());
        if (c.pitch > 45f) {
            c.place = true;
            c.px = bx;
            c.py = y;
            c.pz = bz;
        }
        return true;
    }

    private void setMode(Mode m) {
        if (m != mode) {
            mode = m;
            modeTicks = 0;
            if (m != Mode.RETREAT) burstTicks = 0;
        }
    }

    /**
     * Chance that something it would consider every tick happens at least once
     * since its last decision (decisions are spaced by difficulty, so per-tick
     * odds are compounded over the gap and its habits keep their frequency).
     */
    private boolean chance(double perTick) {
        if (perTick <= 0) return false;
        if (perTick >= 1) return true;
        return rng.nextDouble() < 1 - Math.pow(1 - perTick, Math.max(1, decisionSpan));
    }

    private void choose(Self me, Target t, double dist, double dy) {
        // Multi-tick actions finish (or time out) on their own.
        if (building() || mode == Mode.ROD || mode == Mode.SNOWBALL) return;
        double adapt = diff.adaptWeight();
        boolean canBuild = me.planks >= 2 && buildCooldown == 0 && me.onGround;

        // The player is up high: steps up to them from a distance, a pillar right beside them.
        if (canBuild && dy >= 2.5 && dist < 9 && t.visible) {
            int h = (int) Math.min(4, Math.ceil(dy));
            if (dist > 2.6 && startStairs(me, t, h)) return;
            startTower(me, Math.min(3, h), dist > 2.6);
            return;
        }
        // Stuck (a wall, a pit, a box built around it): get up and out.
        if (canBuild && stuckTicks > 12 && me.collided) {
            startTower(me, 1, false);
            return;
        }
        // No way across on foot (a gap or a drop): bridge toward them.
        if (me.planks >= 1 && buildCooldown == 0 && me.onGround && mode == Mode.CHASE && path.isEmpty()
                && dist > 3 && gapAhead(me, t)) {
            startBridge(me, t);
            return;
        }
        // Being comboed up close: break it with a block between them, or a quick step up.
        if (canBuild && hitsInRow >= 3 && dist < 4.5 && rng.nextDouble() < 0.7 * buildMul() * jitterBuild) {
            hitsInRow = 0;
            if (rng.nextBoolean() && dist > 1.6) startWall(me, t);
            else startTower(me, 1, false);
            return;
        }
        // Hit from range: put a wall up as cover before closing in again.
        if (coverWanted) {
            coverWanted = false;
            if (canBuild && dist > 5 && dist < 20 && rng.nextDouble() < 0.5 * buildMul() * jitterBuild) {
                startWall(me, t);
                return;
            }
        }
        // Open ground at mid range: take the high ground for a moment and throw down.
        if (canBuild && t.visible && dist > 7 && dist < 16 && Math.abs(dy) < 1.5 && me.snowballs > 4
                && chance(0.012 * buildMul() * jitterBuild)) {
            startTower(me, 2, true);
            return;
        }
        // Losing a close trade: get up out of reach (and throw down), or wall them off.
        if (canBuild && mode == Mode.MELEE && dist < 4 && me.health <= 12 && me.health < targetHealth - 3
                && chance(0.05 * buildMul() * jitterBuild)) {
            if (rng.nextBoolean()) startTower(me, 2, me.snowballs > 0);
            else {
                startWall(me, t);
                retreatAfterWall = true;
            }
            return;
        }
        if (mode == Mode.RETREAT) {
            // Defending: back to the normal game once things look better (gradually, see updateDefense).
            if (defense <= 0.3) {
                setMode(t.visible && dist < 7 ? Mode.MELEE : Mode.CHASE);
                return;
            }
            if (dist < 18) {
                // Under pressure: block them off or get up out of reach.
                if (canBuild && dist < 6.5 && chance(0.26 * buildMul() * jitterBuild)) {
                    if (rng.nextBoolean() && dist > 2.2) {
                        startWall(me, t);
                        retreatAfterWall = true;
                    } else startTower(me, 2, me.snowballs > 0);
                    return;
                }
                // Coming in at it: the rod stops a rush and buys space.
                boolean closing = t.visible && closingSpeed(me) > 0.08;
                if (t.visible && me.hasRod && rodCooldown == 0 && dist > 3.4 && dist < 8 && closing && chance(0.2)) {
                    startRod();
                    return;
                }
                if (me.snowballs > 0 && snowCooldown == 0 && dist > 4.5 && t.visible) {
                    startSnowball(2);
                    return;
                }
                return; // keep defending
            }
        }
        if (defense >= 0.55 && dist < 14) {
            setMode(Mode.RETREAT);
            return;
        }
        // Mid range: rod or snowballs to open them up (more of them when it is hurt).
        double ranged = 1 + defense;
        if (t.visible && me.hasRod && rodCooldown == 0 && dist > 3.6 && dist < 12
                && chance(rodChance(adapt) * jitterRod * ranged)) {
            startRod();
            return;
        }
        boolean targetBuilding = dy > 1.2 && !t.onGround;
        if (t.visible && me.snowballs > 0 && snowCooldown == 0 && dist > 6 && dist < 24
                && chance(snowChance(adapt, targetBuilding) * jitterSnow * ranged)) {
            startSnowball(1 + rng.nextInt(3));
            return;
        }
        setMode(t.visible && dist < 7 ? Mode.MELEE : Mode.CHASE);
    }

    /** After a throw or a cast: back to defending if it still should, otherwise the fight. */
    private void afterItem() {
        setMode(defense >= 0.45 ? Mode.RETREAT : Mode.CHASE);
    }

    /** How fast the player is closing in (blocks per tick, from what it saw). */
    private double closingSpeed(Self me) {
        double dx = me.x - lastTx;
        double dz = me.z - lastTz;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-6 || Double.isNaN(d)) return 0;
        return (tvx * dx + tvz * dz) / d;
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
        boolean moved = Double.isNaN(planGoalX) || Math.abs(planGoalX - t.x) + Math.abs(planGoalZ - t.z) > 2;
        if (--replanTimer <= 0 || moved || pathIndex >= path.size()) {
            replanTimer = tempo.vary(tempo.replanTicks(), rng, 2);
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
        c.yaw = turnYaw(me.yaw, moveYaw);
        c.pitch = approach(me.pitch, 5f, 10f);
        steer(c, moveYaw, 1f);
        c.sprint = true;
        if (me.onGround && (nextY > fy || (me.collided && stuckTicks > 3))) c.jump = true;
    }

    // ---- Close combat.

    private void melee(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.SWORD; // switching takes effect this tick, as a hotbar key does
        aimAt(me, t, c, true);
        double range = preferredRange();
        float toTarget = (float) yawTo(me.x, me.z, t.x, t.z);
        float forward;
        if (wtapTicks > 0) {
            wtapTicks--;
            forward = 0f; // release W: the next hit is a sprint hit again
            keyRelease = true;
        } else if (dist > range + 0.5 || mistake(0) || (recoverTicks > 0 && dist > range)) {
            forward = 1f; // close in (or, overcommitting, walk right into them)
        } else if (dist < range - 0.7) {
            forward = -0.4f;
        } else {
            forward = 0.8f + (float) rng.nextDouble() * 0.2f;
        }
        // Don't walk straight into a sprinting player facing us: side-step first.
        boolean theyRush = sidestepRush && t.sprinting && facing(t, me) < 25 && dist > 3.1 && dist < 4.6;
        if (--strafeTimer <= 0) {
            // Fairly regular switches with a favourite side: readable, and so counterable.
            strafeTimer = (int) (strafeTicks() * tempo.strafeIntervalMul() * (0.85 + rng.nextDouble() * 0.3));
            double sameSide = 0.5 * sideBias + 0.5 * (0.5 + (profile.strafeLeft - 0.5) * diff.adaptWeight());
            strafeDir = rng.nextDouble() < sameSide ? 1 : -1;
            sidestepRush = rng.nextDouble() < 0.45 + 0.35 * diff.skill();
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
        if (!engaged) return; // still reacting to them coming into reach (no pre-swinging either)
        if (dist <= REACH + 0.35 && clickCooldown == 0 && aimError(me, t) < 30
                && (t.hurtTime <= window || falling || mistake(1))) {
            c.attack = true;
            clickCooldown = clickGap();
        } else if (dist <= REACH + 1.5 && clickCooldown == 0 && rng.nextDouble() < 0.3) {
            // Players click a little early too; a miss just swings.
            c.attack = true;
            clickCooldown = clickGap();
        }
    }

    /**
     * Engagement, from what it perceives each tick (whatever it is doing). When the
     * opponent first comes into reach a short reaction timer runs before the first
     * swing; it isn't restarted while they stay close, survives brief steps out of
     * reach, and is shorter when re-engaging someone it was just fighting.
     */
    private void updateEngagement(Target t, double dist) {
        clock++;
        if (t.id != engagedTargetId) {
            // A different opponent: a fresh first contact.
            engagedTargetId = t.id;
            engaged = false;
            engageDelay = -1;
            outOfReachTicks = 0;
            disengagedAt = -1000;
        }
        // First contact counts from real reach (3 blocks), not the slightly early click margin.
        boolean inReach = dist <= (engaged ? REACH + 0.35 : REACH) && t.visible;
        if (inReach) {
            outOfReachTicks = 0;
            if (engaged) return;
            if (engageDelay < 0) engageDelay = firstSwingDelay(clock - disengagedAt < 40);
            if (engageDelay > 0) engageDelay--;
            if (engageDelay == 0) {
                engaged = true;
                engageDelay = -1;
            }
            return;
        }
        outOfReachTicks++;
        if (engaged) {
            // A moment out of reach is still the same fight (knockback, strafing).
            if (outOfReachTicks > 14 && dist > REACH + 1.5) {
                engaged = false;
                disengagedAt = clock;
            }
        } else if (outOfReachTicks > 6) {
            engageDelay = -1; // they backed off before it reacted: start over next time
        }
    }

    /**
     * Ticks from "in reach" to the first swing, on top of the perception lag
     * ({@link #reaction()}): about 300-500 ms on Easy down to 100-150 ms on
     * Expert, varied, and shorter against an opponent it was fighting a moment ago.
     */
    int firstSwingDelay(boolean recent) {
        double base = tempo.firstSwingTicks() * tempo.focus(); // ~6.4 on Easy, ~2.5 on Expert
        if (recent) base *= 0.5;
        int d = (int) Math.round(base + (rng.nextDouble() * 2.0 - 0.5) * (recent ? 0.6 : 1.0));
        return Math.max(1, Math.min(10, d));
    }

    /**
     * Defensive fighting when it is losing: it stays in the fight and inside the
     * arena, holding just outside the player's reach, facing them and strafing
     * hard, hitting back when they step in. When it is being comboed or rushed it
     * may break away for a moment (a short sprint, curving away from the border),
     * then turns back. How far it holds off grows with {@link #defense}.
     */
    private void retreat(Self me, Target t, double dist, Controls c) {
        c.slot = Slot.SWORD;
        if (burstTicks > 0) {
            burst(me, dist, c);
            return;
        }
        boolean rushed = t.visible && t.sprinting && facing(t, me) < 30 && dist < 4;
        if (burstCooldown == 0 && defense > 0.7 && (hitsInRow >= 2 || rushed)) {
            float yaw = escapeYaw(me, t);
            if (!Float.isNaN(yaw)) {
                burstTicks = 14 + rng.nextInt(10);
                burstYaw = yaw;
                burstCooldown = 60 + rng.nextInt(40);
                hitsInRow = 0;
                burst(me, dist, c);
                return;
            }
        }
        if (dist <= REACH + 0.35 && t.visible) {
            // They came in: hit back, without walking further into them.
            melee(me, t, dist, c);
            if (c.forward > 0.3f) c.forward = 0.3f;
            c.sprint = false;
            return;
        }
        aimAt(me, t, c, true);
        double hold = REACH + 0.6 + 1.2 * defense;
        float forward;
        if (dist < hold - 0.4) forward = -1f;
        else if (dist > hold + 2.0) forward = 0.8f;
        else if (dist > hold + 0.6) forward = 0.4f;
        else forward = 0f;
        if (--strafeTimer <= 0) {
            strafeTimer = Math.max(4, (int) (strafeTicks() * tempo.strafeIntervalMul() * (1 - 0.35 * defense)
                    * (0.8 + rng.nextDouble() * 0.4)));
            if (rng.nextDouble() < 0.65) strafeDir = -strafeDir;
        }
        c.forward = forward;
        c.strafe = strafeDir;
        c.sprint = forward >= 0.8f;
        if (me.onGround && me.collided) c.jump = true;
    }

    /** A short sprint away along the chosen line, ending early when far enough or short of room. */
    private void burst(Self me, double dist, Controls c) {
        burstTicks--;
        c.yaw = turnYaw(me.yaw, burstYaw);
        c.pitch = approach(me.pitch, 0f, 10f);
        steer(c, burstYaw, 1f);
        c.sprint = true;
        if (me.onGround && me.collided) c.jump = true;
        if (dist > 7.5 || edgeDistance(me.x, me.z) < 4) burstTicks = 0;
    }

    /**
     * The best direction to break away in: away from the player, or off to the
     * side if that leads somewhere better, never toward the border, a drop or
     * lava. NaN when there is nowhere sensible to go.
     */
    private float escapeYaw(Self me, Target t) {
        float away = (float) yawTo(t.x, t.z, me.x, me.z);
        float best = Float.NaN;
        double bestScore = -1e9;
        int fy = (int) Math.floor(me.y + 0.01);
        for (int i = 0; i < 12; i++) {
            float off = -110f + i * 20f;
            float yaw = away + off;
            double r = Math.toRadians(yaw);
            double sx = -Math.sin(r);
            double sz = Math.cos(r);
            boolean ok = true;
            for (int step = 2; step <= 6 && ok; step += 2) {
                double px = me.x + sx * step;
                double pz = me.z + sz * step;
                if (edgeDistance(px, pz) < 5) ok = false;
                int bx = floor(px);
                int bz = floor(pz);
                if (!terrain.open(bx, fy, bz) || !terrain.open(bx, fy + 1, bz)) ok = false; // a wall
                else if (terrain.hazard(bx, fy - 1, bz) || terrain.hazard(bx, fy, bz)) ok = false;
                else if (terrain.open(bx, fy - 1, bz) && terrain.open(bx, fy - 2, bz) && terrain.open(bx, fy - 3, bz)) ok = false; // a drop
            }
            if (!ok) continue;
            double end = edgeDistance(me.x + sx * 6, me.z + sz * 6);
            double score = Math.cos(Math.toRadians(off)) + 0.3 * Math.min(end, 20) / 20 + rng.nextDouble() * 0.15;
            if (score > bestScore) {
                bestScore = score;
                best = yaw;
            }
        }
        return best;
    }

    /** Distance to the nearest arena edge (infinite when the arena isn't known). */
    double edgeDistance(double x, double z) {
        if (Double.isNaN(arenaMinX)) return Double.POSITIVE_INFINITY;
        return Math.min(Math.min(x - arenaMinX, arenaMaxX - x), Math.min(z - arenaMinZ, arenaMaxZ - z));
    }

    /**
     * Near the border, take out the part of the movement that heads outward (and
     * nudge inward right at it), whatever it is doing. The look direction is
     * unchanged; only the movement keys are.
     */
    private void keepInside(Self me, Controls c) {
        if (Double.isNaN(arenaMinX)) return;
        double nx = 0;
        double nz = 0;
        double ex1 = me.x - arenaMinX;
        double ex2 = arenaMaxX - me.x;
        double ez1 = me.z - arenaMinZ;
        double ez2 = arenaMaxZ - me.z;
        if (ex1 < EDGE_MARGIN) nx += (EDGE_MARGIN - ex1) / EDGE_MARGIN;
        if (ex2 < EDGE_MARGIN) nx -= (EDGE_MARGIN - ex2) / EDGE_MARGIN;
        if (ez1 < EDGE_MARGIN) nz += (EDGE_MARGIN - ez1) / EDGE_MARGIN;
        if (ez2 < EDGE_MARGIN) nz -= (EDGE_MARGIN - ez2) / EDGE_MARGIN;
        double w = Math.sqrt(nx * nx + nz * nz);
        if (w < 1e-6) return;
        nx /= w;
        nz /= w;
        // World-space movement from the keys (vanilla moveFlying).
        double yr = Math.toRadians(c.yaw);
        double sin = Math.sin(yr);
        double cos = Math.cos(yr);
        double mx = c.strafe * cos - c.forward * sin;
        double mz = c.forward * cos + c.strafe * sin;
        double outward = -(mx * nx + mz * nz);
        if (outward > 0) {
            double k = outward * Math.min(1, w * 1.5);
            mx += nx * k;
            mz += nz * k;
        }
        if (w > 0.6) {
            mx += nx * 0.4 * w;
            mz += nz * 0.4 * w;
        }
        float f = (float) (mz * cos - mx * sin);
        float st = (float) (mx * cos + mz * sin);
        c.forward = Math.max(-1f, Math.min(1f, f));
        c.strafe = Math.max(-1f, Math.min(1f, st));
        if (c.forward < 0.8f) c.sprint = false;
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
            if (shot != null && Math.abs(wrap(c.yaw - me.yaw)) < 6 && !me.hookOut) {
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
        afterItem();
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
            snowCooldown = 25 + rng.nextInt(45);
            afterItem();
            c.slot = Slot.SWORD;
            return;
        }
        Ballistics.Shot shot = aimProjectile(me, t, Ballistics.Kind.SNOWBALL, c);
        if (shot != null && clickCooldown == 0 && Math.abs(wrap(c.yaw - me.yaw)) < 5) {
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

    // ---- Building with planks (always through the entity's survival placement rules).

    private void startTower(Self me, int height, boolean snowAfter) {
        setMode(Mode.TOWER);
        towerBase = (int) Math.floor(me.y + 0.01);
        towerLeft = Math.max(1, height);
        snowAfterTower = snowAfter;
        buildCooldown = 25 + rng.nextInt(20);
        stuckTicks = 0;
    }

    /** Pillar up: look down, jump, place a plank under your feet at the top of the jump. */
    private void tower(Self me, Target t, Controls c) {
        c.slot = Slot.PLANKS;
        c.yaw = me.yaw;
        c.pitch = approach(me.pitch, 90f, turnRate());
        int bx = floor(me.x);
        int bz = floor(me.z);
        boolean headroom = terrain.open(bx, towerBase + 2, bz) && terrain.open(bx, towerBase + 3, bz);
        if (me.planks <= 0 || towerLeft <= 0 || modeTicks > 20 + 20 * Math.max(1, towerLeft) || !headroom) {
            finishTower(me, t, c);
            return;
        }
        if (me.pitch < 70) return;
        if (me.onGround) {
            if (me.y >= towerBase + 0.99) {
                // Landed on the new block.
                towerBase = (int) Math.floor(me.y + 0.01);
                if (--towerLeft <= 0) {
                    finishTower(me, t, c);
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

    /** Up on the pillar: throw down at them if that was the idea, otherwise back to the fight. */
    private void finishTower(Self me, Target t, Controls c) {
        double dx = t.x - me.x;
        double dz = t.z - me.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (snowAfterTower && t.visible && me.snowballs > 0 && dist > 3.2 && dist < 20) {
            startSnowball(2 + rng.nextInt(2));
        } else {
            if (defense >= 0.45) setMode(Mode.RETREAT);
            else setMode(t.visible && dist < 6 ? Mode.MELEE : Mode.CHASE);
        }
        snowAfterTower = false;
        c.slot = Slot.SWORD;
    }

    /** A 3-wide barrier toward the player (two high in the middle), placed in a few quick clicks. */
    private void startWall(Self me, Target t) {
        setMode(Mode.WALL);
        int[] d = axisToward(me, t);
        int bx = floor(me.x);
        int bz = floor(me.z);
        int y = (int) Math.floor(me.y + 0.01);
        int cx = bx + d[0];
        int cz = bz + d[1];
        int px = -d[1];
        int pz = d[0];
        wallCount = 0;
        addWallCell(cx, y, cz);
        addWallCell(cx, y + 1, cz);
        addWallCell(cx + px, y, cz + pz);
        addWallCell(cx - px, y, cz - pz);
        wallIndex = 0;
        buildCooldown = 30 + rng.nextInt(15);
    }

    private void addWallCell(int x, int y, int z) {
        wallCells[wallCount * 3] = x;
        wallCells[wallCount * 3 + 1] = y;
        wallCells[wallCount * 3 + 2] = z;
        wallCount++;
    }

    private void wall(Self me, Target t, Controls c) {
        c.slot = Slot.PLANKS;
        c.forward = 0;
        while (wallIndex < wallCount) {
            int x = wallCells[wallIndex * 3];
            int y = wallCells[wallIndex * 3 + 1];
            int z = wallCells[wallIndex * 3 + 2];
            if (!terrain.open(x, y, z) || !supported(x, y, z)) {
                wallIndex++; // already blocked, or nothing to place it against
                continue;
            }
            break;
        }
        if (me.planks <= 0 || wallIndex >= wallCount || modeTicks > 40) {
            setMode(retreatAfterWall || defense >= 0.45 ? Mode.RETREAT : Mode.MELEE);
            retreatAfterWall = false;
            c.slot = Slot.SWORD;
            return;
        }
        int i = wallIndex * 3;
        if (placeAt(me, c, wallCells[i], wallCells[i + 1], wallCells[i + 2])) wallIndex++;
    }

    /** Steps up toward a player standing higher: fill the column ahead, step onto it, repeat. */
    private boolean startStairs(Self me, Target t, int height) {
        int[] d = axisToward(me, t);
        int nx = floor(me.x) + d[0];
        int nz = floor(me.z) + d[1];
        int base = (int) Math.floor(me.y + 0.01);
        if (!terrain.open(nx, base + 1, nz) || !terrain.open(nx, base + 2, nz)) return false;
        setMode(Mode.STAIRS);
        stairDX = d[0];
        stairDZ = d[1];
        stairsLeft = height;
        stairBackoff = 0;
        lastStepY = Math.floor(me.y + 0.01);
        buildCooldown = 25 + rng.nextInt(20);
        return true;
    }

    private void stairs(Self me, Target t, Controls c) {
        c.slot = Slot.PLANKS;
        if (me.planks <= 0 || stairsLeft <= 0 || modeTicks > 30 + 30 * stairsLeft || t.y - me.y < 0.9) {
            setMode(t.visible ? Mode.MELEE : Mode.CHASE);
            c.slot = Slot.SWORD;
            return;
        }
        if (stairBackoff > 0) {
            // Too close to the spot (the block would go into itself): step back a little.
            stairBackoff--;
            c.yaw = turnYaw(me.yaw, stairYaw());
            c.forward = -0.4f;
            return;
        }
        int bx = floor(me.x);
        int bz = floor(me.z);
        int base = (int) Math.floor(me.y + 0.01);
        int nx = bx + stairDX;
        int nz = bz + stairDZ;
        if (!terrain.open(nx, base + 1, nz) || !terrain.open(nx, base + 2, nz)) {
            setMode(Mode.CHASE); // no headroom on that side
            c.slot = Slot.SWORD;
            return;
        }
        if (me.onGround && terrain.open(nx, base, nz)) {
            // Fill the next column up to our feet, from the bottom (at most two blocks).
            int y = base;
            while (y > base - 2 && terrain.open(nx, y - 1, nz)) y--;
            if (!supported(nx, y, nz)) {
                setMode(Mode.CHASE);
                c.slot = Slot.SWORD;
                return;
            }
            c.forward = 0;
            placeAt(me, c, nx, y, nz);
            return;
        }
        // The step is there: jump onto it.
        float yaw = stairYaw();
        c.yaw = turnYaw(me.yaw, yaw);
        c.pitch = approach(me.pitch, 20f, turnRate());
        steer(c, yaw, 1f);
        if (me.onGround) c.jump = true;
        if (me.onGround && me.y > lastStepY + 0.9) {
            lastStepY = me.y;
            stairsLeft--;
        }
    }

    private float stairYaw() {
        return (float) yawTo(0, 0, stairDX, stairDZ);
    }

    private void startBridge(Self me, Target t) {
        setMode(Mode.BRIDGE);
        int[] d = axisToward(me, t);
        stairDX = d[0];
        stairDZ = d[1];
        bridgeLeft = 8;
        buildCooldown = 25 + rng.nextInt(15);
    }

    /** Across a gap or a drop: a plank against the edge in front, walk onto it, repeat. */
    private void bridge(Self me, Target t, Controls c) {
        c.slot = Slot.PLANKS;
        int bx = floor(me.x);
        int bz = floor(me.z);
        int base = (int) Math.floor(me.y + 0.01);
        int nx = bx + stairDX;
        int nz = bz + stairDZ;
        boolean done = me.planks <= 0 || bridgeLeft <= 0 || modeTicks > 100 || !me.onGround && me.my < -0.3;
        if (done || terrain.solid(nx, base - 1, nz) && modeTicks > 0 && !terrain.open(nx, base - 1, nz) && bridgeLeft < 8) {
            setMode(Mode.CHASE);
            c.slot = Slot.SWORD;
            return;
        }
        if (terrain.open(nx, base - 1, nz)) {
            c.forward = 0;
            if (placeAt(me, c, nx, base - 1, nz)) bridgeLeft--;
            return;
        }
        // Walk onto the new plank (slowly, no sprint).
        float yaw = stairYaw();
        c.yaw = turnYaw(me.yaw, yaw);
        c.pitch = approach(me.pitch, 60f, turnRate());
        steer(c, yaw, 0.6f);
    }

    /** Can't get there on foot: the ground ahead toward them drops away by 3 or more. */
    private boolean gapAhead(Self me, Target t) {
        int[] d = axisToward(me, t);
        int nx = floor(me.x) + d[0];
        int nz = floor(me.z) + d[1];
        int base = (int) Math.floor(me.y + 0.01);
        if (!terrain.open(nx, base, nz) || !terrain.open(nx, base + 1, nz)) return false;
        for (int k = 1; k <= 3; k++) if (!terrain.open(nx, base - k, nz)) return false;
        return terrain.solid(floor(me.x), base - 1, floor(me.z));
    }

    /** One of the four directions, the one closest to the way to the player. */
    private static int[] axisToward(Self me, Target t) {
        double dx = t.x - me.x;
        double dz = t.z - me.z;
        if (Math.abs(dx) >= Math.abs(dz)) return new int[]{dx >= 0 ? 1 : -1, 0};
        return new int[]{0, dz >= 0 ? 1 : -1};
    }

    /** A face to place against (a solid neighbour), as the vanilla rule needs. */
    private boolean supported(int x, int y, int z) {
        return terrain.solid(x + 1, y, z) || terrain.solid(x - 1, y, z) || terrain.solid(x, y + 1, z)
                || terrain.solid(x, y - 1, z) || terrain.solid(x, y, z + 1) || terrain.solid(x, y, z - 1);
    }

    /**
     * Look at the block spot and right-click once lined up (and the right-click
     * rate allows). Returns true on the tick the placement is requested.
     */
    private boolean placeAt(Self me, Controls c, int x, int y, int z) {
        double ex = x + 0.5 - me.x;
        double ey = y + 0.5 - (me.y + EYE);
        double ez = z + 0.5 - me.z;
        float yaw = (float) yawTo(me.x, me.z, x + 0.5, z + 0.5);
        float pitch = (float) -Math.toDegrees(Math.atan2(ey, Math.sqrt(ex * ex + ez * ez)));
        c.yaw = turnYaw(me.yaw, yaw);
        c.pitch = approach(me.pitch, pitch, turnRate());
        if (placeCooldown > 0 || Math.sqrt(ex * ex + ey * ey + ez * ez) > PLACE_REACH) return false;
        if (Math.abs(wrap(c.yaw - yaw)) < 20 && Math.abs(c.pitch - pitch) < 20) {
            c.place = true;
            c.px = x;
            c.py = y;
            c.pz = z;
            placeCooldown = 2 + rng.nextInt(2) + tempo.vary(tempo.placeGapTicks(), rng, 0); // right-click rate, plus picking the next spot
            return true;
        }
        return false;
    }

    /** The entity reports whether the last requested block went down. */
    public void onPlaceResult(boolean placed) {
        if (!placed && mode == Mode.STAIRS) stairBackoff = 3;
    }

    // ---- Aiming.

    /** Aim at the target's body, leading its movement a little (reacting late, with noise). */
    private void aimAt(Self me, Target t, Controls c, boolean body) {
        double lead = diff.leadTicks();
        double sideLead = (profile.strafeLeft - 0.5) * diff.adaptWeight() * 0.3;
        double px = t.x + tvx * lead;
        double pz = t.z + tvz * lead;
        double py = t.y + (body ? 1.2 : 1.0) + tvy * lead;
        double noise = aimNoise() * (rng.nextDouble() - 0.5) * 0.6;
        float yaw = (float) (yawTo(me.x, me.z, px, pz) + noise + sideLead);
        double ddx = px - me.x;
        double ddz = pz - me.z;
        float pitch = (float) -Math.toDegrees(Math.atan2(py - (me.y + EYE), Math.sqrt(ddx * ddx + ddz * ddz)));
        c.yaw = turnYaw(me.yaw, yaw);
        c.pitch = approach(me.pitch, pitch, turnRate());
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
        double noise = aimNoise() * (rng.nextDouble() - 0.5) * 0.4;
        c.yaw = turnYaw(me.yaw, (float) (yaw + noise));
        c.pitch = approach(me.pitch, pitch, turnRate());
        return shot;
    }

    private double aimError(Self me, Target t) {
        return Math.abs(wrap((float) yawTo(me.x, me.z, t.x, t.z) - me.yaw));
    }

    // ---- Tendencies (style, learning, skill).

    private double preferredRange() {
        double r = 2.85 + jitterRange + rangeDrift;
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
        return Math.max(2.2, Math.min(3.25, r));
    }

    private int strafeTicks() {
        return style == Playstyle.STRAFER ? 12 : (style == Playstyle.DEFENSIVE ? 30 : 22);
    }

    private double retreatHealth() {
        return style == Playstyle.AGGRESSIVE ? 3 : (style == Playstyle.DEFENSIVE ? 8 : 6);
    }

    /** Ticks between seeing something and acting on it: ~350 ms on Easy down to 50-100 ms on Expert. */
    int reaction() {
        return perceptionDelay;
    }

    /** Aim wander, a little wider than the Sumo opponent's; much wider during a sloppy moment. */
    private double aimNoise() {
        return diff.aimNoiseDeg() * 1.2 * (mistake(1) ? 3 : 1);
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
        int delay = reaction();
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
