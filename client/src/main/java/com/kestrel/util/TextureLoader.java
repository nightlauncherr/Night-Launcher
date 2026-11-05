package com.kestrel.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Decodes validated user PNGs (off the game thread) and uploads them as
 * dynamic textures (on the game thread).
 */
public final class TextureLoader {
    private TextureLoader() {
    }

    /**
     * Decode a PNG whose header has already been validated. Uses the PNG
     * reader explicitly so no other ImageIO plugin parses untrusted data.
     * Call off the game thread.
     */
    public static BufferedImage decodePng(File file) throws IOException {
        ImageInputStream in = ImageIO.createImageInputStream(file);
        if (in == null) {
            throw new IOException("cannot open " + file.getName());
        }
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) {
                throw new IOException("no PNG decoder available");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } finally {
            in.close();
        }
    }

    /** Upload an image as a texture. Must run on the game thread. */
    public static ResourceLocation upload(String name, BufferedImage image) {
        DynamicTexture tex = new DynamicTexture(image);
        return Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation(name, tex);
    }

    /**
     * Free a texture created by {@link #upload}. Game thread only.
     *
     * <p>Vanilla's {@code TextureManager.deleteTexture} frees only the GL
     * texture: the entry stays in the texture map with its old (now freed) GL
     * id and, for dynamic textures, a full copy of the pixels. So every cape,
     * sky or skin switch leaked memory, and the stale id could alias a texture
     * the driver later hands out under the same id. Here the GL texture is
     * freed through the texture object (which forgets its id) and the entry is
     * removed from the map.
     */
    public static void delete(ResourceLocation loc) {
        if (loc == null) return;
        TextureManager tm = Minecraft.getMinecraft().getTextureManager();
        ITextureObject tex = tm.getTexture(loc);
        if (tex == null) return;
        if (tex instanceof AbstractTexture) {
            ((AbstractTexture) tex).deleteGlTexture();
        } else {
            tm.deleteTexture(loc);
        }
        Map<Object, Object> map = textureMap(tm, loc, tex);
        if (map != null) map.remove(loc);
    }

    /** TextureManager's location -> texture map, found once by content (names differ between dev and release). */
    private static java.lang.reflect.Field textureMapField;
    private static boolean textureMapMissing;

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> textureMap(TextureManager tm, ResourceLocation loc, ITextureObject tex) {
        try {
            if (textureMapField == null) {
                if (textureMapMissing) return null;
                for (java.lang.reflect.Field f : TextureManager.class.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object m = f.get(tm);
                    if (m instanceof Map && ((Map<Object, Object>) m).get(loc) == tex) {
                        textureMapField = f;
                        break;
                    }
                }
                if (textureMapField == null) {
                    textureMapMissing = true;
                    com.kestrel.KestrelMod.LOG.warn("[Kestrel] texture map not found; freed textures stay registered");
                    return null;
                }
            }
            return (Map<Object, Object>) textureMapField.get(tm);
        } catch (ReflectiveOperationException | RuntimeException e) {
            textureMapMissing = true;
            com.kestrel.KestrelMod.LOG.warn("[Kestrel] cannot access the texture map: {}", e.toString());
            return null;
        }
    }

    /** Whether a resource (e.g. a bundled texture) exists. */
    public static boolean resourceExists(ResourceLocation loc) {
        try {
            Minecraft.getMinecraft().getResourceManager().getResource(loc).getInputStream().close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
