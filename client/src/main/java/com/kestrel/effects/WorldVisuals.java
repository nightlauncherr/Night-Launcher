package com.kestrel.effects;

import com.kestrel.KestrelClient;
import com.kestrel.animation.FirstPersonAnimator;
import com.kestrel.config.ClientConfig;
import com.kestrel.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityEgg;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.potion.Potion;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Client-side combat and world visuals: hit sparks on your own hits,
 * projectile trails and arrow impacts, and experience orb glow. Purely visual:
 * nothing here sends packets or changes entities. Every feature has its own
 * switch in {@link ClientConfig.Visuals}; with all of them off nothing is
 * tracked or drawn.
 *
 * <p>Tracking is bounded ({@link #MAX_TRAILS}, {@link #MAX_SPARKS},
 * {@link #MAX_ORBS}) and trails reuse their position buffers, so a volley of
 * arrows can never grow memory or draw cost without limit.
 */
public final class WorldVisuals {
    static final int MAX_TRAILS = 48;
    static final int MAX_SPARKS = 16;
    static final int MAX_ORBS = 64;
    private static final int TRAIL_LEN = 12;
    private static final int TRAIL_FADE_TICKS = 6;
    private static final int SPARK_TICKS = 6;
    private static final int SPARK_RAYS = 9;
    private static final double ORB_RANGE_SQ = 32.0 * 32.0;

    private final KestrelClient client;
    private final Map<Entity, Trail> trails = new IdentityHashMap<Entity, Trail>();
    private final List<Trail> pool = new ArrayList<Trail>();
    private final List<Spark> sparks = new ArrayList<Spark>();
    private final Random rng = new Random();
    private final float[] buf = new float[(TRAIL_LEN + 1) * 3];
    private final float[] ray = new float[2 * 3];
    private boolean orbsNearby;

    public WorldVisuals(KestrelClient client) {
        this.client = client;
    }

    private ClientConfig.Visuals cfg() {
        return client.config().visuals;
    }

    // ------------------------------------------------------------------ hits

    /** Your own melee hit on an entity (fired client-side by the vanilla attack code). */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP me = mc.thePlayer;
        if (me == null || event.entityPlayer != me || event.target == null || !cfg().hitSparks) return;
        if (!me.worldObj.isRemote) return;
        Entity t = event.target;
        boolean crit = me.fallDistance > 0f && !me.onGround && !me.isOnLadder() && !me.isInWater()
                && !me.isPotionActive(Potion.blindness) && me.ridingEntity == null;
        if (sparks.size() >= MAX_SPARKS) sparks.remove(0);
        // Where the hit lands: the point of the target's box facing you, at chest height.
        double dx = me.posX - t.posX;
        double dz = me.posZ - t.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double r = t.width * 0.5;
        double hx = t.posX + (len > 1e-3 ? dx / len * r : 0);
        double hz = t.posZ + (len > 1e-3 ? dz / len * r : 0);
        Spark s = new Spark(hx, t.posY + t.height * 0.6, hz, crit);
        for (int k = 0; k < SPARK_RAYS; k++) {
            float ux = (float) rng.nextGaussian();
            float uy = (float) rng.nextGaussian() + 0.4f;
            float uz = (float) rng.nextGaussian();
            float l = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (l < 1e-4f) l = 1f;
            float sp = (crit ? 0.55f : 0.4f) * (0.6f + rng.nextFloat() * 0.6f);
            s.dir[k * 3] = ux / l * sp;
            s.dir[k * 3 + 1] = uy / l * sp;
            s.dir[k * 3 + 2] = uz / l * sp;
        }
        sparks.add(s);
    }

    // ----------------------------------------------------------------- ticks

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        WorldClient w = Minecraft.getMinecraft().theWorld;
        if (w == null) {
            clear();
            return;
        }
        for (int i = sparks.size() - 1; i >= 0; i--) {
            if (++sparks.get(i).age >= SPARK_TICKS) sparks.remove(i);
        }
        ClientConfig.Visuals v = cfg();
        orbsNearby = v.xpOrbGlow && anyOrb(w.loadedEntityList);
        if (!v.arrowTrails && !v.arrowImpacts) {
            if (!trails.isEmpty()) clearTrails();
            return;
        }
        // Existing trails: record the new head, detect landing, retire dead ones.
        for (Iterator<Map.Entry<Entity, Trail>> it = trails.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Trail> e = it.next();
            Trail tr = e.getValue();
            Entity ent = e.getKey();
            if (tr.fading >= 0) {
                if (++tr.fading >= TRAIL_FADE_TICKS) {
                    it.remove();
                    pool.add(tr);
                }
                continue;
            }
            boolean moved = Math.abs(ent.posX - tr.lastX) + Math.abs(ent.posY - tr.lastY) + Math.abs(ent.posZ - tr.lastZ) > 1e-3;
            if (ent.isDead || !moved) {
                if (!ent.isDead && ent instanceof EntityArrow && v.arrowImpacts) tr.impact = 0;
                tr.fading = 0;
                continue;
            }
            tr.push(ent.posX, ent.posY, ent.posZ);
        }
        // New projectiles.
        List<Entity> list = w.loadedEntityList;
        for (int i = 0; i < list.size() && trails.size() < MAX_TRAILS; i++) {
            Entity ent = list.get(i);
            if (!(ent instanceof EntityArrow || ent instanceof EntityThrowable) || ent.isDead || trails.containsKey(ent)) {
                continue;
            }
            // Only projectiles in flight: it must actually have moved this tick. (A 1.8 arrow
            // stuck in a block keeps a leftover motion value, so motion alone is not enough.)
            double dx = ent.posX - ent.prevPosX, dy = ent.posY - ent.prevPosY, dz = ent.posZ - ent.prevPosZ;
            if (dx * dx + dy * dy + dz * dz < 0.01) continue;
            Trail tr = pool.isEmpty() ? new Trail() : pool.remove(pool.size() - 1);
            tr.reset(ent.posX, ent.posY, ent.posZ, colourOf(ent));
            trails.put(ent, tr);
        }
    }

    /** Checked once per tick so frames without orbs skip the entity list entirely. */
    private static boolean anyOrb(List<Entity> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof EntityXPOrb) return true;
        }
        return false;
    }

    private static int colourOf(Entity e) {
        if (e instanceof EntityArrow) return ((EntityArrow) e).getIsCritical() ? 0xFFB347 : 0xDDEBFF;
        if (e instanceof EntityEnderPearl) return 0x3FE0C0;
        if (e instanceof EntityPotion) return 0xFF7AD9;
        if (e instanceof EntitySnowball) return 0xFFFFFF;
        if (e instanceof EntityEgg) return 0xFFE9B0;
        return 0xDDEBFF;
    }

    // ---------------------------------------------------------------- render

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        ClientConfig.Visuals v = cfg();
        boolean orbs = v.xpOrbGlow && orbsNearby;
        if (sparks.isEmpty() && trails.isEmpty() && !orbs) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        float pt = event.partialTicks;
        double cx = RenderUtil.camX(pt);
        double cy = RenderUtil.camY(pt);
        double cz = RenderUtil.camZ(pt);
        Fx.begin(cx, cy, cz, cx, cy, cz); // origin at the camera: coordinates below are camera-relative
        try {
            if (v.hitSparks) drawSparks(pt, cx, cy, cz);
            drawTrails(pt, cx, cy, cz, v);
            if (orbs) drawOrbs(mc, pt, cx, cy, cz);
        } finally {
            Fx.end();
        }
    }

    private void drawSparks(float pt, double cx, double cy, double cz) {
        for (int i = 0; i < sparks.size(); i++) {
            Spark s = sparks.get(i);
            float t = (s.age + pt) / SPARK_TICKS;
            if (t >= 1f) continue;
            float a = (1f - t) * (1f - t);
            float sx = (float) (s.x - cx), sy = (float) (s.y - cy), sz = (float) (s.z - cz);
            int core = s.crit ? 0xFFE7A0 : 0xFFFFFF;
            int glow = s.crit ? 0xFF9A2A : 0x9FD0FF;
            Fx.glow(sx, sy, sz, s.crit ? 0.55f : 0.4f, glow, 0.7f * a);
            float head = 0.15f + 0.85f * (1f - (1f - t) * (1f - t) * (1f - t));
            float tail = Math.max(0f, head - 0.45f);
            for (int k = 0; k < SPARK_RAYS; k++) {
                float dx = s.dir[k * 3], dy = s.dir[k * 3 + 1], dz = s.dir[k * 3 + 2];
                ray[0] = sx + dx * head;
                ray[1] = sy + dy * head - 0.15f * t * t;
                ray[2] = sz + dz * head;
                ray[3] = sx + dx * tail;
                ray[4] = sy + dy * tail;
                ray[5] = sz + dz * tail;
                Fx.ribbon(ray, 2, 0.06f, 0.0f, core, glow, a, 0f);
            }
        }
    }

    private void drawTrails(float pt, double cx, double cy, double cz, ClientConfig.Visuals v) {
        for (Map.Entry<Entity, Trail> e : trails.entrySet()) {
            Trail tr = e.getValue();
            Entity ent = e.getKey();
            float fade = tr.fading < 0 ? 1f : 1f - (tr.fading + pt) / TRAIL_FADE_TICKS;
            if (fade <= 0f) continue;
            if (v.arrowTrails && tr.count > 1) {
                int n = 0;
                if (tr.fading < 0) {
                    // Live head between ticks, so the trail stays attached to the projectile.
                    buf[0] = (float) (ent.lastTickPosX + (ent.posX - ent.lastTickPosX) * pt - cx);
                    buf[1] = (float) (ent.lastTickPosY + (ent.posY - ent.lastTickPosY) * pt - cy);
                    buf[2] = (float) (ent.lastTickPosZ + (ent.posZ - ent.lastTickPosZ) * pt - cz);
                    n = 1;
                }
                for (int k = 0; k < tr.count; k++) {
                    int idx = (tr.head - k + TRAIL_LEN) % TRAIL_LEN;
                    buf[n * 3] = (float) (tr.xs[idx] - cx);
                    buf[n * 3 + 1] = (float) (tr.ys[idx] - cy);
                    buf[n * 3 + 2] = (float) (tr.zs[idx] - cz);
                    n++;
                }
                Fx.ribbon(buf, n, 0.12f, 0.0f, 0xFFFFFF, tr.color, 0.75f * fade, 0f);
                Fx.ribbon(buf, n, 0.35f, 0.0f, tr.color, tr.color, 0.25f * fade, 0f);
            }
            if (tr.impact >= 0 && tr.fading >= 0) {
                float it = (tr.fading + pt) / TRAIL_FADE_TICKS;
                float ix = (float) (tr.lastX - cx), iy = (float) (tr.lastY - cy), iz = (float) (tr.lastZ - cz);
                Fx.glow(ix, iy, iz, 0.25f + it * 0.5f, tr.color, 0.8f * (1f - it));
                Fx.billboardRing(ix, iy, iz, 0.1f + it * 0.6f, 0.08f, 0xFFFFFF, 0.8f * (1f - it));
            }
        }
    }

    private void drawOrbs(Minecraft mc, float pt, double cx, double cy, double cz) {
        List<Entity> list = mc.theWorld.loadedEntityList;
        int drawn = 0;
        long now = Minecraft.getSystemTime();
        for (int i = 0; i < list.size() && drawn < MAX_ORBS; i++) {
            Entity e = list.get(i);
            if (!(e instanceof EntityXPOrb) || e.isDead) continue;
            double ex = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
            double ey = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + 0.12;
            double ez = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
            double dx = ex - cx, dy = ey - cy, dz = ez - cz;
            if (dx * dx + dy * dy + dz * dz > ORB_RANGE_SQ) continue;
            float pulse = 0.75f + 0.25f * (float) Math.sin(now / 180.0 + e.getEntityId());
            Fx.glow(dx, dy, dz, 0.45f * pulse, 0xB8FF5A, 0.45f);
            drawn++;
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) {
            clear();
            FirstPersonAnimator.reset();
        }
    }

    private void clear() {
        orbsNearby = false;
        clearTrails();
        sparks.clear();
    }

    private void clearTrails() {
        pool.addAll(trails.values());
        trails.clear();
    }

    /** Ring buffer of a projectile's recent positions (reused through the pool). */
    private static final class Trail {
        final double[] xs = new double[TRAIL_LEN];
        final double[] ys = new double[TRAIL_LEN];
        final double[] zs = new double[TRAIL_LEN];
        int head;
        int count;
        int color;
        /** -1 while flying; counts up once it has landed or vanished. */
        int fading;
        /** 0 when an arrow landed and an impact burst should show. */
        int impact;
        double lastX, lastY, lastZ;

        void reset(double x, double y, double z, int color) {
            head = 0;
            count = 0;
            fading = -1;
            impact = -1;
            this.color = color;
            push(x, y, z);
        }

        void push(double x, double y, double z) {
            head = (head + 1) % TRAIL_LEN;
            xs[head] = x;
            ys[head] = y;
            zs[head] = z;
            if (count < TRAIL_LEN) count++;
            lastX = x;
            lastY = y;
            lastZ = z;
        }
    }

    private static final class Spark {
        final double x, y, z;
        final boolean crit;
        final float[] dir = new float[SPARK_RAYS * 3];
        int age;

        Spark(double x, double y, double z, boolean crit) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.crit = crit;
        }
    }
}
