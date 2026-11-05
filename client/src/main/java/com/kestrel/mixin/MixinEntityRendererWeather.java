package com.kestrel.mixin;

import com.kestrel.combat.QolHooks;
import com.kestrel.rendering.RefinedWeather;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rain and snow presentation (all calls verified against the 1.8.9 bytecode).
 *
 * <ul>
 * <li>{@code renderRainSnow} binds {@code textures/environment/rain.png} and
 * {@code snow.png} (one {@code bindTexture} call each). "Refined weather"
 * swaps in Kestrel's textures; Weather Customizer's rain or snow switch swaps
 * in a fully transparent one, which vanilla's alpha test discards (see
 * {@link RefinedWeather#swap}).</li>
 * <li>{@code renderRainSnow} reads {@code WorldClient.getRainStrength} once;
 * it sets the rain and snow opacity, so the intensity setting scales it.</li>
 * <li>{@code addRainParticles} reads the rain strength once (splash count and
 * whether to play rain sounds) and calls {@code spawnParticle} and
 * {@code playSound} twice each; the particle and sound switches skip those
 * calls. Lightning bolts and thunder are server entities and are not changed.</li>
 * </ul>
 * All of it is local presentation: the world's weather and time are untouched.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererWeather {
    @Redirect(method = "renderRainSnow", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V"))
    private void kestrel$weatherTexture(TextureManager tm, ResourceLocation loc) {
        tm.bindTexture(RefinedWeather.swap(loc));
    }

    @Redirect(method = "renderRainSnow", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getRainStrength(F)F"))
    private float kestrel$weatherStrength(WorldClient world, float partialTicks) {
        return QolHooks.weatherStrength(world.getRainStrength(partialTicks));
    }

    @Redirect(method = "addRainParticles", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getRainStrength(F)F"))
    private float kestrel$splashStrength(WorldClient world, float partialTicks) {
        // Splashes and rain sounds belong to rain, so hiding rain hides them too.
        return QolHooks.hideRain() ? 0f : QolHooks.weatherStrength(world.getRainStrength(partialTicks));
    }

    @Redirect(method = "addRainParticles", require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V"))
    private void kestrel$splashParticle(WorldClient world, EnumParticleTypes type, double x, double y, double z,
                                        double vx, double vy, double vz, int[] args) {
        if (QolHooks.weatherParticles()) world.spawnParticle(type, x, y, z, vx, vy, vz, args);
    }

    @Redirect(method = "addRainParticles", require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;playSound(DDDLjava/lang/String;FFZ)V"))
    private void kestrel$rainSound(WorldClient world, double x, double y, double z, String sound,
                                   float volume, float pitch, boolean distanceDelay) {
        if (QolHooks.weatherSounds()) world.playSound(x, y, z, sound, volume, pitch, distanceDelay);
    }
}
