package com.kestrel.cosmetics;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;

/**
 * Night's own cape geometry: the same box and UVs as vanilla
 * {@code ModelPlayer.bipedCape} (10x16x1 on a 64x32 sheet, hanging from the
 * shoulders), but a separate model that Night owns.
 *
 * <p>Night capes are drawn with this instead of {@code ModelPlayer.renderCape}
 * because other cape mods replace that method: WaveyCapes (1.8.9) cancels it
 * for every caller and draws only the official cape itself, which left an
 * equipped Night cape invisible in game and in the menu previews. Nothing
 * outside Night touches this model, so equipping a Night cape is enough for
 * it to draw, whatever other cape mods are installed and whether or not the
 * account owns an official cape.
 *
 * <p>Render thread only (the display list is compiled on first use).
 */
public final class NightCapeModel extends ModelBase {
    private static NightCapeModel instance;

    private final ModelRenderer cape;

    private NightCapeModel() {
        textureWidth = 64;
        textureHeight = 32;
        cape = new ModelRenderer(this, 0, 0);
        cape.setTextureSize(64, 32);
        cape.addBox(-5.0F, 0.0F, -1.0F, 10, 16, 1);
    }

    /** Draw the cape box with the bound texture, as {@code ModelPlayer.renderCape(scale)} would. */
    public static void render(float scale) {
        if (instance == null) instance = new NightCapeModel();
        instance.cape.render(scale);
    }
}
