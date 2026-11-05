package com.kestrel.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-game client settings, persisted as JSON in {@code <gameDir>/kestrel/config.json}.
 *
 * <p>Every field has a default so that missing keys (older files, partial
 * edits) keep sensible values; {@link #validate()} clamps everything after
 * loading. All classes keep an implicit no-arg constructor so Gson applies the
 * field initialisers.
 */
public final class ClientConfig {
    public static final int CURRENT_VERSION = 1;

    public int configVersion = CURRENT_VERSION;
    /** Preset most recently applied, for display only. */
    public String activePreset = Preset.COMPETITIVE.id;
    /** Last preset requested by the launcher that we applied (see PresetApplier). */
    public String lastLauncherPreset = "";
    /** Revision of the last launcher cosmetics request applied (see LauncherSync). */
    public long lastLauncherCosmeticsRevision = 0;

    public Map<String, HudElementConfig> hud = new LinkedHashMap<String, HudElementConfig>();
    public Crosshair crosshair = new Crosshair();
    public Sprint sprint = new Sprint();
    public Cosmetics cosmetics = new Cosmetics();
    public Sky sky = new Sky();
    public KillEffects killEffects = new KillEffects();
    public Performance performance = new Performance();
    public Qol qol = new Qol();
    public Zoom zoom = new Zoom();
    public FreeLook freeLook = new FreeLook();
    public Animations animations = new Animations();
    public Visuals visuals = new Visuals();

    public static final class HudElementConfig {
        public boolean enabled = false;
        /** Top-left position as a fraction of the scaled screen size (0..1). */
        public float x = 0.01f;
        public float y = 0.01f;
        public float scale = 1.0f;
        public boolean background = true;
        public int textColor = 0xFFFFFFFF;
        public boolean textShadow = true;

        public HudElementConfig() {
        }

        public HudElementConfig(boolean enabled, float x, float y) {
            this.enabled = enabled;
            this.x = x;
            this.y = y;
        }

        void validate() {
            x = clamp(x, 0f, 1f);
            y = clamp(y, 0f, 1f);
            scale = clamp(scale, 0.5f, 3.0f);
            textColor |= 0xFF000000;
        }
    }

    public static final class Crosshair {
        public boolean custom = false;
        /** 0 = cross, 1 = dot, 2 = cross with dot, 3 = T-shape. */
        public int style = 0;
        public int length = 5;
        public int thickness = 2;
        public int gap = 3;
        public int color = 0xFFFFFFFF;
        public boolean outline = true;
        public int outlineColor = 0xFF000000;
        public boolean hideInThirdPerson = true;

        void validate() {
            style = clampi(style, 0, 3);
            length = clampi(length, 1, 20);
            thickness = clampi(thickness, 1, 8);
            gap = clampi(gap, 0, 15);
            color |= 0xFF000000;
            outlineColor |= 0xFF000000;
        }
    }

    public static final class Sprint {
        public boolean toggleSprint = true;
        /** Persisted toggle state so sprint stays on across sessions. */
        public boolean toggled = false;
    }

    public static final class Cosmetics {
        public boolean capesEnabled = true;
        /** Cape id: "none", a catalog id (capes.json), or "custom:<file name>" (PNG or GIF). */
        public String cape = "none";
        public boolean animatedCapes = true;
        /** Draw capes the bundled catalog assigns to specific players (by UUID) on those players. */
        public boolean showAssignedCapes = true;
        /** Frames per second for animated capes. */
        public int animationFps = 10;

        void validate() {
            if (cape == null || cape.isEmpty()) {
                cape = "none";
            }
            animationFps = clampi(animationFps, 1, 30);
        }
    }

    public static final class Sky {
        public boolean enabled = false;
        /** Sky id: bundled preset id or "custom:<file name>". */
        public String preset = "dusk";
        public float brightness = 1.0f;
        public boolean rotate = true;
        /** Degrees per second when rotating. */
        public float rotationSpeed = 0.5f;
        /** Dim the custom sky at night using the world's sun angle. */
        public boolean followDayCycle = false;

        void validate() {
            if (preset == null || preset.isEmpty()) {
                preset = "dusk";
            }
            brightness = clamp(brightness, 0.1f, 1.0f);
            rotationSpeed = clamp(rotationSpeed, 0f, 10f);
        }
    }

    public static final class KillEffects {
        public boolean enabled = true;
        /** {@link com.kestrel.effects.KillEffectType} id. */
        public String effect = "burst";
        public float intensity = 1.0f;
        /** Upper bound on particles a single effect may spawn. */
        public int maxParticles = 60;
        public boolean sounds = true;
        public float volume = 0.7f;
        /** Colour used by the colour effects (helix, colour burst). Opaque ARGB. */
        public int color = 0xFFE8833A;
        /** Credit kills from the server's death message when it makes you the killer (KillMessageMatcher). */
        public boolean chatDetection = true;
        /** Credit kills when a player dies right after your hit with no other damage in between (KillAttribution). */
        public boolean healthDetection = true;

        void validate() {
            if (effect == null || effect.isEmpty()) {
                effect = "burst";
            }
            intensity = clamp(intensity, 0.25f, 2.0f);
            maxParticles = clampi(maxParticles, 0, 200);
            volume = clamp(volume, 0f, 1f);
            color |= 0xFF000000;
        }
    }

    public static final class Performance {
        /** When applying a preset, also change vanilla video settings. Off by default. */
        public boolean applyVanillaSettings = false;
        /** How often HUD text is rebuilt, in milliseconds. */
        public int hudRefreshMs = 100;

        void validate() {
            hudRefreshMs = clampi(hudRefreshMs, 16, 1000);
        }
    }

    /**
     * Quality-of-life and PvP-awareness features (in-game Kestrel Settings, QoL).
     * All client-side: none of them changes attacks, reach, packets or server state.
     */
    public static final class Qol {
        /** Full lightmap brightness (see {@link com.kestrel.rendering.Fullbright}). */
        public boolean fullbright = false;
        public HitColor hitColor = new HitColor();
        public HitDelayFix hitDelayFix = new HitDelayFix();
        public LowHealthAlert lowHealthAlert = new LowHealthAlert();
        public HealthPrediction healthPrediction = new HealthPrediction();
        public BlockCounter blockCounter = new BlockCounter();
        public BridgeAssist bridgeAssist = new BridgeAssist();
        public ClearWater clearWater = new ClearWater();
        public NoBlindness noBlindness = new NoBlindness();
        public Weather weather = new Weather();

        void validate() {
            if (hitColor == null) hitColor = new HitColor();
            if (hitDelayFix == null) hitDelayFix = new HitDelayFix();
            if (lowHealthAlert == null) lowHealthAlert = new LowHealthAlert();
            if (healthPrediction == null) healthPrediction = new HealthPrediction();
            if (blockCounter == null) blockCounter = new BlockCounter();
            if (bridgeAssist == null) bridgeAssist = new BridgeAssist();
            if (clearWater == null) clearWater = new ClearWater();
            if (noBlindness == null) noBlindness = new NoBlindness();
            if (weather == null) weather = new Weather();
            hitColor.validate();
            lowHealthAlert.validate();
            healthPrediction.validate();
            blockCounter.validate();
            bridgeAssist.validate();
            clearWater.validate();
            weather.validate();
        }
    }

    /** Tint a player you have just hit (see {@link com.kestrel.combat.CombatTracker}). */
    public static final class HitColor {
        public boolean enabled = false;
        public int color = 0xFFE8833A;
        /** Seconds the tint lasts. */
        public float duration = 0.5f;
        /** Strength of the tint at its peak (0..1). */
        public float intensity = 0.45f;
        /** Fade out over the duration; off = full strength until it ends. */
        public boolean fade = true;

        void validate() {
            color |= 0xFF000000;
            duration = clamp(duration, 0.1f, 2.0f);
            intensity = clamp(intensity, 0.1f, 0.9f);
        }
    }

    /** Attack feedback (see {@link com.kestrel.combat.QolHooks#missLockout}). */
    public static final class HitDelayFix {
        public boolean enabled = false;
        /** Remove vanilla's 0.5 s click lock after a miss (client-side only). */
        public boolean removeMissLock = true;
        /** Restart the swing animation on every click instead of only after half a swing. */
        public boolean instantSwing = true;
    }

    /** Alert when your current target's health drops to a threshold. */
    public static final class LowHealthAlert {
        public boolean enabled = false;
        /** Health points (2 = one heart). */
        public float threshold = 6.0f;
        public boolean sound = true;
        /** Always show the target's (server-reported) health under the alert. */
        public boolean showHealth = true;
        public int color = 0xFFFF5555;

        void validate() {
            threshold = clamp(threshold, 1.0f, 19.0f);
            color |= 0xFF000000;
        }
    }

    /** Estimated opponent health from observed hits (see {@link com.kestrel.combat.HealthEstimator}). */
    public static final class HealthPrediction {
        public boolean enabled = false;
        /** Assumed starting health when the server's health value can't be trusted. */
        public float startingHealth = 20.0f;
        /** Colour of estimated values (confirmed values are white). */
        public int estimateColor = 0xFFFFAA00;

        void validate() {
            startingHealth = clamp(startingHealth, 1.0f, 40.0f);
            estimateColor |= 0xFF000000;
        }
    }

    /** Hotbar block count (position, scale, colour and background are the HUD element's). */
    public static final class BlockCounter {
        /** Count only the categories switched on below. */
        public boolean onlySelected = false;
        public boolean wool = true;
        public boolean clay = true;
        public boolean wood = true;
        public boolean stone = true;
        public boolean glass = true;
        public boolean other = true;
        /** Draw a block icon next to the number. */
        public boolean icon = true;
        /** Also show the stack in your hand on its own. */
        public boolean showHeld = false;

        void validate() {
        }
    }

    /** Visual bridging aid (see {@link com.kestrel.combat.BridgeAssist}). */
    public static final class BridgeAssist {
        public boolean enabled = false;
        public int color = 0xFF55FF55;
        public float opacity = 0.35f;
        /** Outline thickness in pixels. */
        public float size = 2.0f;
        /** Edge marker showing how close you are to the edge you are bridging from. */
        public boolean alignmentGuide = true;
        /** Translucent preview of the block that would be placed. */
        public boolean placementPreview = true;

        void validate() {
            color |= 0xFF000000;
            opacity = clamp(opacity, 0.1f, 1.0f);
            size = clamp(size, 1.0f, 5.0f);
        }
    }

    /** Clearer underwater view (fog and overlay only; water blocks are unchanged). */
    public static final class ClearWater {
        public boolean enabled = false;
        /** How much of the underwater fog to remove (0..1). */
        public float clarity = 0.7f;
        /** Lift the underwater fog colour (0 = vanilla dark blue). */
        public float brightness = 0.3f;
        /** How much of the blue screen overlay to remove (0..1). */
        public float overlayTransparency = 0.6f;

        void validate() {
            clarity = clamp(clarity, 0f, 0.95f);
            brightness = clamp(brightness, 0f, 1f);
            overlayTransparency = clamp(overlayTransparency, 0f, 1f);
        }
    }

    /** Hide blindness's black fog on your screen (the effect itself stays). */
    public static final class NoBlindness {
        public boolean enabled = false;
    }

    /** Local weather presentation (server weather is untouched). */
    public static final class Weather {
        public boolean enabled = false;
        public boolean rain = true;
        public boolean snow = true;
        /** Splash particles where rain lands. */
        public boolean particles = true;
        public boolean sounds = true;
        /** 0..1 strength of the rain and snow drawn. */
        public float intensity = 1.0f;
        /** Draw the world as if the weather were clear (sky, fog and light too). */
        public boolean clearSky = false;

        void validate() {
            intensity = clamp(intensity, 0.1f, 1.0f);
        }
    }

    /** Hold-to-zoom (default key C; see {@link com.kestrel.camera.CameraController}). */
    public static final class Zoom {
        /** How far to zoom in: the field of view is divided by this. */
        public float factor = 4.0f;
        /** Transition speed (exponential rate per second; higher is faster). */
        public float speed = 14.0f;
        /** Animate zooming in and out; off switches instantly. */
        public boolean smooth = true;
        /** Lower mouse sensitivity while zoomed, in proportion to the zoom. */
        public boolean scaleSensitivity = false;

        void validate() {
            factor = clamp(factor, 1.5f, 10.0f);
            speed = clamp(speed, 2.0f, 40.0f);
        }
    }

    /** 360-degree free-look (default key Left Alt). */
    public static final class FreeLook {
        /** Switch to third person while held (back to first person afterwards). */
        public boolean thirdPerson = true;
        /** Ease the camera back on release instead of returning instantly. */
        public boolean smoothReturn = true;
    }

    /**
     * First-person animations (see {@link com.kestrel.animation.FirstPersonAnimator}).
     * Visual only: attacks, their timing, reach and rotation are never changed.
     */
    public static final class Animations {
        /** Custom sword animation; off = Minecraft's own. */
        public boolean swordEnabled = true;
        /** {@link com.kestrel.animation.SwingStyle} id for swords. */
        public String swordStyle = "kestrel";
        /** Glowing slash trail (Kestrel slash style). */
        public boolean trail = true;
        /** Trail colour from the sword's texture; off = the Kestrel accent colour. */
        public boolean trailItemColor = true;
        /** Custom animation for axes, pickaxes, shovels, hoes and fishing rods. */
        public boolean toolsEnabled = true;
        /** {@link com.kestrel.animation.SwingStyle} id for tools. */
        public String toolStyle = "smooth";
        /** Animation speed multiplier (1 = vanilla's 0.3 s swing). */
        public float speed = 1.0f;
        /** How big the motion is. */
        public float intensity = 1.0f;
        /** Ease item switching instead of vanilla's linear drop and raise. */
        public boolean smoothSwitch = true;
        /** Ease into and out of the sword block pose. */
        public boolean smoothBlock = true;
        /** Show a (visual only) swing when you click while blocking, 1.7 style. */
        public boolean swingWhileBlocking = false;
        /** Very small idle sway of the held sword or tool. */
        public boolean idleSway = true;

        void validate() {
            if (swordStyle == null) swordStyle = "kestrel";
            if (toolStyle == null) toolStyle = "smooth";
            speed = clamp(speed, 0.5f, 2.0f);
            intensity = clamp(intensity, 0.5f, 1.5f);
        }
    }

    /** Extra client-side visuals; each can be switched off on its own. */
    public static final class Visuals {
        /** Small sparks where your hits land (gold for critical hits). */
        public boolean hitSparks = true;
        /** Fading trails behind arrows in flight. */
        public boolean arrowTrails = true;
        /** A small burst where an arrow lands. */
        public boolean arrowImpacts = true;
        /** Soft glow around experience orbs. */
        public boolean xpOrbGlow = true;
        /** Kestrel's finer rain and snow textures. */
        public boolean refinedWeather = false;
    }

    /** Clamp every value into a safe range and repair nulls. */
    public void validate() {
        if (hud == null) {
            hud = new LinkedHashMap<String, HudElementConfig>();
        }
        for (Map.Entry<String, HudElementConfig> e : hud.entrySet()) {
            if (e.getValue() == null) {
                e.setValue(new HudElementConfig());
            }
            e.getValue().validate();
        }
        if (crosshair == null) crosshair = new Crosshair();
        if (sprint == null) sprint = new Sprint();
        if (cosmetics == null) cosmetics = new Cosmetics();
        if (sky == null) sky = new Sky();
        if (killEffects == null) killEffects = new KillEffects();
        if (performance == null) performance = new Performance();
        if (qol == null) qol = new Qol();
        if (zoom == null) zoom = new Zoom();
        if (freeLook == null) freeLook = new FreeLook();
        if (animations == null) animations = new Animations();
        if (visuals == null) visuals = new Visuals();
        if (Preset.byId(activePreset) == null) activePreset = Preset.COMPETITIVE.id;
        if (lastLauncherPreset == null) lastLauncherPreset = "";
        crosshair.validate();
        cosmetics.validate();
        sky.validate();
        killEffects.validate();
        performance.validate();
        zoom.validate();
        animations.validate();
        qol.validate();
    }

    /** Returns the element config, creating it with the given defaults if absent. */
    public HudElementConfig hudElement(String id, boolean enabledByDefault, float x, float y) {
        HudElementConfig c = hud.get(id);
        if (c == null) {
            c = new HudElementConfig(enabledByDefault, x, y);
            hud.put(id, c);
        }
        return c;
    }

    static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static int clampi(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
