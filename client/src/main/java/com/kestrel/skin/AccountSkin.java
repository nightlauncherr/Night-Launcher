package com.kestrel.skin;

import com.kestrel.KestrelClient;
import com.kestrel.KestrelMod;
import com.kestrel.util.AsyncLoader;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The signed-in account's own skin (or Steve/Alex when offline), looked up
 * once per session through Mojang's session server without credentials and
 * cached. Game thread only; the lookup runs on the loader thread.
 */
public final class AccountSkin {
    private static ResourceLocation texture;
    private static boolean slim;
    private static boolean requested;

    private AccountSkin() {
    }

    /** Look the skin up again on next use (the session changed). Game thread. */
    public static void reset() {
        requested = false;
        texture = null;
        slim = false;
    }

    /** Start the lookup once; until it finishes the default skin is used. */
    public static void request(KestrelClient client) {
        if (requested) return;
        requested = true;
        final Minecraft mc = Minecraft.getMinecraft();
        final GameProfile profile = mc.getSession().getProfile();
        if (profile.getId() != null) {
            texture = DefaultPlayerSkin.getDefaultSkin(profile.getId());
            slim = "slim".equals(DefaultPlayerSkin.getSkinType(profile.getId()));
        } else {
            texture = DefaultPlayerSkin.getDefaultSkinLegacy();
        }
        if (profile.getId() == null) return;
        client.loader().submit(new Callable<GameProfile>() {
            @Override
            public GameProfile call() {
                return mc.getSessionService().fillProfileProperties(profile, false);
            }
        }, new AsyncLoader.Callback<GameProfile>() {
            @Override
            public void done(GameProfile filled, Throwable error) {
                if (error != null || filled == null) return; // offline: keep the default skin
                try {
                    Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = mc.getSkinManager().loadSkinFromCache(filled);
                    MinecraftProfileTexture tex = map.get(MinecraftProfileTexture.Type.SKIN);
                    if (tex != null) {
                        texture = mc.getSkinManager().loadSkin(tex, MinecraftProfileTexture.Type.SKIN);
                        slim = "slim".equals(tex.getMetadata("model"));
                    }
                } catch (RuntimeException e) {
                    KestrelMod.LOG.debug("[Kestrel] account skin unavailable: {}", e.toString());
                }
            }
        });
    }

    /** The account skin texture (the default skin until the lookup finishes). */
    public static ResourceLocation texture() {
        return texture != null ? texture : DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    public static boolean slim() {
        return slim;
    }
}
