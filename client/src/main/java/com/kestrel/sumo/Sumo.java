package com.kestrel.sumo;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;

/**
 * Entry points for the Sumo practice mode (playtest feature). Everything Sumo
 * lives in this package; the rest of the client only calls these four methods.
 */
public final class Sumo {
    private Sumo() {
    }

    /** Pre-init (client): the opponent's renderer. */
    public static void preInit() {
        RenderingRegistry.registerEntityRenderingHandler(EntitySumoBot.class, new IRenderFactory<EntitySumoBot>() {
            @Override
            public Render<? super EntitySumoBot> createRenderFor(RenderManager manager) {
                return new RenderSumoBot(manager);
            }
        });
    }

    /** Init: the opponent entity, server-side session hooks and the Singleplayer button. */
    public static void init(Object mod) {
        EntityRegistry.registerModEntity(EntitySumoBot.class, "NightSumoBot", 0, mod, 80, 1, true);
        MinecraftForge.EVENT_BUS.register(SumoServer.get());
        MinecraftForge.EVENT_BUS.register(new SumoMenuButton());
    }

    public static void serverStarting(FMLServerStartingEvent event) {
        SumoServer.get().serverStarting(event);
    }

    public static void serverStopping() {
        SumoServer.get().serverStopping();
    }
}
