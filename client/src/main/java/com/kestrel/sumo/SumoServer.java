package com.kestrel.sumo;

import com.kestrel.KestrelMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;

/**
 * Starts and stops Sumo sessions with the integrated server. A session only
 * exists while the dedicated Sumo world ({@link SumoArena#WORLD_FOLDER}) is
 * loaded; every other world is untouched. All of this runs on the server thread.
 */
public final class SumoServer {
    private static final SumoServer INSTANCE = new SumoServer();

    private final File dataDir;
    private SumoSession session;

    private SumoServer() {
        this.dataDir = KestrelMod.client() != null ? KestrelMod.client().dataDir()
                : new File(net.minecraft.client.Minecraft.getMinecraft().mcDataDir, "kestrel");
    }

    public static SumoServer get() {
        return INSTANCE;
    }

    File statsFile() {
        return SumoStats.file(dataDir);
    }

    SumoSession session() {
        return session;
    }

    /** FMLServerStartingEvent: worlds are loaded. */
    public void serverStarting(FMLServerStartingEvent event) {
        stopSession("new server");
        MinecraftServer server = event.getServer();
        if (!SumoArena.WORLD_FOLDER.equals(server.getFolderName())) return;
        WorldServer overworld = server.worldServerForDimension(0);
        if (overworld == null) return;
        session = new SumoSession(overworld, statsFile(), KestrelMod.LOG);
        session.start();
        event.registerServerCommand(new SumoCommand(this));
    }

    /** FMLServerStoppingEvent: leaving the world, by any route. */
    public void serverStopping() {
        stopSession("server stopping");
    }

    private void stopSession(String reason) {
        if (session != null) {
            session.stop(reason);
            session = null;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && session != null) session.tick();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (session != null && event.player instanceof EntityPlayerMP) session.onPlayerJoin((EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null) {
            session.onPlayerLeave(event.player);
            if (session.phase() == SumoSession.Phase.STOPPED) session = null;
        }
    }

    /** Hits between the combatants keep knockback and the hurt flash but never cost health. */
    @SubscribeEvent
    public void onHurt(LivingHurtEvent event) {
        SumoSession s = session;
        if (s == null || event.entityLiving.worldObj != s.world() || event.entityLiving.worldObj.isRemote) return;
        if (!s.isCombatant(event.entityLiving)) return;
        s.onHurt(event.entityLiving, event.source.getEntity());
        event.setCanceled(true);
    }
}
