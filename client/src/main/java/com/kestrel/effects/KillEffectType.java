package com.kestrel.effects;

/**
 * Available kill effects. Ids are stored in the config and must match the
 * launcher's list ({@code KILL_EFFECTS} in kestrel-core's cosmetics.rs).
 */
public enum KillEffectType {
    // id, label, category, sound, pitch, uses the colour setting
    BURST("burst", "Particle burst", Category.CLASSIC, "random.orb", 0.6f, false),
    RING("ring", "Particle ring", Category.CLASSIC, "random.orb", 0.6f, false),
    HELIX("helix", "Colour helix", Category.CLASSIC, "random.orb", 0.6f, true),
    DUST("dust", "Colour burst", Category.CLASSIC, "random.orb", 0.6f, true),
    EXPLOSION("explosion", "Explosion", Category.CLASSIC, "random.explode", 1.2f, false),
    SMOKE("smoke", "Smoke bomb", Category.CLASSIC, "random.fizz", 0.6f, false),
    PIXEL("pixel", "Pixel blast", Category.CLASSIC, "random.pop", 0.8f, false),
    LIGHTNING("lightning", "Lightning", Category.ELEMENTAL, "ambient.weather.thunder", 1.4f, false),
    FLAMES("flames", "Flame pillar", Category.ELEMENTAL, "mob.ghast.fireball", 1.0f, false),
    SHOCK("shock", "Electric shock", Category.ELEMENTAL, "random.fizz", 1.8f, false),
    ICE("ice", "Ice shatter", Category.ELEMENTAL, "dig.glass", 1.2f, false),
    NETHER("nether", "Nether blaze", Category.ELEMENTAL, "mob.blaze.hit", 0.8f, false),
    TORNADO("tornado", "Tornado", Category.ELEMENTAL, "mob.enderdragon.wings", 1.2f, false),
    ARCANE("arcane", "Arcane", Category.MAGIC, "mob.endermen.portal", 1.2f, false),
    SOUL("soul", "Soul release", Category.MAGIC, "mob.ghast.moan", 1.6f, false),
    ENDER("ender", "Ender vanish", Category.MAGIC, "mob.endermen.portal", 0.8f, false),
    PORTAL("portal", "Portal", Category.MAGIC, "mob.endermen.portal", 0.6f, false),
    DRAGON("dragon", "Dragon breath", Category.MAGIC, "mob.enderdragon.growl", 1.6f, false),
    ENERGY("energy", "Energy burst", Category.MAGIC, "random.orb", 0.5f, true),
    HEARTS("hearts", "Hearts", Category.CELEBRATION, "random.levelup", 1.6f, false),
    FIREWORKS("fireworks", "Fireworks", Category.CELEBRATION, "fireworks.blast", 1.0f, true),
    CONFETTI("confetti", "Confetti", Category.CELEBRATION, "fireworks.twinkle", 1.2f, false),
    RAINBOW("rainbow", "Rainbow rings", Category.CELEBRATION, "random.orb", 1.4f, false),
    STARFALL("starfall", "Falling stars", Category.CELEBRATION, "fireworks.twinkle", 1.6f, false),
    KESTREL("kestrel", "Kestrel strike", Category.KESTREL, "mob.bat.takeoff", 0.7f, false),
    // Advanced: real 3D geometry (AdvancedEffects / AdvancedEffects2).
    BOLT_STRIKE("bolt_strike", "Lightning strike", Category.ADVANCED, "ambient.weather.thunder", 1.2f, false),
    ENERGY_NOVA("energy_nova", "Energy explosion", Category.ADVANCED, "random.explode", 1.6f, true),
    BLACK_HOLE("black_hole", "Black hole", Category.ADVANCED, "mob.endermen.portal", 0.5f, false),
    DRAGON_BURST("dragon_burst", "Dragon burst", Category.ADVANCED, "mob.enderdragon.growl", 1.4f, false),
    CRYSTAL_BURST("crystal_burst", "Crystal explosion", Category.ADVANCED, "dig.glass", 0.9f, false),
    FIRE_TORNADO("fire_tornado", "Fire tornado", Category.ADVANCED, "mob.ghast.fireball", 0.7f, false),
    FROST_SPIKES("frost_spikes", "Frost spikes", Category.ADVANCED, "dig.glass", 1.5f, false),
    PORTAL_RIFT("portal_rift", "Portal rift", Category.ADVANCED, "portal.trigger", 1.6f, false),
    METEOR("meteor", "Meteor impact", Category.ADVANCED, "random.explode", 0.7f, false),
    SPIRIT_ASCEND("spirit_ascend", "Soul ascension", Category.ADVANCED, "mob.ghast.charge", 1.6f, false),
    PLASMA_SPHERE("plasma_sphere", "Electric sphere", Category.ADVANCED, "random.fizz", 1.4f, false),
    DARK_VORTEX("dark_vortex", "Dark energy", Category.ADVANCED, "mob.wither.shoot", 0.5f, false),
    PRISM_BURST("prism_burst", "Rainbow explosion", Category.ADVANCED, "fireworks.largeBlast", 1.2f, false),
    STAR_RAIN("star_rain", "Star rain", Category.ADVANCED, "fireworks.twinkle", 1.8f, false),
    VOID_BURST("void_burst", "Void explosion", Category.ADVANCED, "mob.endermen.portal", 0.4f, false),
    MAGIC_CIRCLE("magic_circle", "Magic circle", Category.ADVANCED, "random.levelup", 1.4f, true),
    FLAME_WINGS("flame_wings", "Flame wings", Category.ADVANCED, "mob.enderdragon.wings", 0.9f, false),
    SHATTERED_DIMENSION("shattered_dimension", "Shattered dimension", Category.ADVANCED, "dig.glass", 0.6f, false),
    NEON_PULSE("neon_pulse", "Neon pulse", Category.ADVANCED, "note.pling", 0.8f, true),
    KESTREL_TALON("kestrel_talon", "Kestrel talon", Category.ADVANCED, "mob.bat.takeoff", 1.1f, false);

    public enum Category {
        CLASSIC("Classic"), ELEMENTAL("Elemental"), MAGIC("Magic"), CELEBRATION("Celebration"), KESTREL("Kestrel"),
        ADVANCED("3D");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public final String id;
    public final String label;
    public final Category category;
    public final String sound;
    public final float pitch;
    /** Whether the effect is tinted by the colour setting. */
    public final boolean usesColor;

    KillEffectType(String id, String label, Category category, String sound, float pitch, boolean usesColor) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.sound = sound;
        this.pitch = pitch;
        this.usesColor = usesColor;
    }

    public static KillEffectType byId(String id) {
        for (KillEffectType t : values()) {
            if (t.id.equals(id)) return t;
        }
        return BURST;
    }
}
