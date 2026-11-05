package com.kestrel.rendering;

import com.kestrel.KestrelClient;
import com.kestrel.KestrelMod;
import com.kestrel.combat.QolHooks;
import net.minecraft.util.ResourceLocation;

/** Texture swap for the optional refined rain and snow (see {@code MixinEntityRendererWeather}). */
public final class RefinedWeather {
    private static final ResourceLocation RAIN = new ResourceLocation("kestrel", "textures/environment/rain.png");
    private static final ResourceLocation SNOW = new ResourceLocation("kestrel", "textures/environment/snow.png");

    /** Fully transparent: Weather Customizer hides rain or snow by drawing this instead. */
    private static final ResourceLocation NONE = new ResourceLocation("kestrel", "textures/environment/none.png");

    private RefinedWeather() {
    }

    /**
     * The texture to draw for vanilla's rain or snow: transparent when Weather
     * Customizer hides it, Kestrel's when "Refined weather" is on, else vanilla's.
     * Any other texture is returned unchanged.
     */
    public static ResourceLocation swap(ResourceLocation loc) {
        KestrelClient c = KestrelMod.client();
        if (c == null || !"minecraft".equals(loc.getResourceDomain())) return loc;
        String path = loc.getResourcePath();
        boolean rain = "textures/environment/rain.png".equals(path);
        boolean snow = !rain && "textures/environment/snow.png".equals(path);
        if (!rain && !snow) return loc;
        if (rain ? QolHooks.hideRain() : QolHooks.hideSnow()) return NONE;
        if (!c.config().visuals.refinedWeather) return loc;
        return rain ? RAIN : SNOW;
    }
}
