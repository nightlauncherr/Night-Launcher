package com.kestrel.openmap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderSnowball;
import net.minecraft.init.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;

/**
 * Entry points for Open Map PvP (playtest feature). Everything lives in this
 * package; the client only calls these four methods. Separate from Sumo.
 */
public final class OpenMap {
    private OpenMap() {
    }

    public static void preInit() {
        RenderingRegistry.registerEntityRenderingHandler(EntityPvpBot.class, new IRenderFactory<EntityPvpBot>() {
            @Override
            public Render<? super EntityPvpBot> createRenderFor(RenderManager manager) {
                return new RenderPvpBot(manager);
            }
        });
        RenderingRegistry.registerEntityRenderingHandler(BotFishHook.class, new IRenderFactory<BotFishHook>() {
            @Override
            public Render<? super BotFishHook> createRenderFor(RenderManager manager) {
                return new RenderBotHook(manager);
            }
        });
        RenderingRegistry.registerEntityRenderingHandler(BotSnowball.class, new IRenderFactory<BotSnowball>() {
            @Override
            public Render<? super BotSnowball> createRenderFor(RenderManager manager) {
                return new RenderSnowball<BotSnowball>(manager, Items.snowball, Minecraft.getMinecraft().getRenderItem());
            }
        });
    }

    public static void init(Object mod) {
        // Ids 1-3 (Sumo's opponent is 0).
        EntityRegistry.registerModEntity(EntityPvpBot.class, "NightPvpBot", 1, mod, 80, 1, true);
        EntityRegistry.registerModEntity(BotFishHook.class, "NightPvpHook", 2, mod, 64, 2, true);
        EntityRegistry.registerModEntity(BotSnowball.class, "NightPvpSnowball", 3, mod, 64, 2, true);
        MinecraftForge.EVENT_BUS.register(OpenMapServer.get());
        MinecraftForge.EVENT_BUS.register(new OpenMapMenuButton());
        MinecraftForge.EVENT_BUS.register(new OpenMapClient());
    }

    public static void serverStarting(FMLServerStartingEvent event) {
        OpenMapServer.get().serverStarting(event);
    }

    public static void serverStopping() {
        OpenMapServer.get().serverStopping();
    }
}
