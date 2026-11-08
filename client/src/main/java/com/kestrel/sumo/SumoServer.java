package com.kestrel.sumo;

import com.kestrel.KestrelMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
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
        session = new SumoSession(overworld, dataDir, KestrelMod.LOG);
        session.start();
        event.registerServerCommand(new SumoCommand(this));
        event.registerServerCommand(new StopSumoCommand(this));
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

    // ---- The map can only be changed by its owner, in edit mode. These checks sit in
    // the server's own block handling, so no command, item or menu gets around them.

    private SumoSession sumoWorld(net.minecraft.world.World w) {
        SumoSession s = session;
        return s != null && w != null && !w.isRemote && w == s.world() ? s : null;
    }

    private void deny(SumoSession s, net.minecraft.entity.player.EntityPlayer p) {
        if (p instanceof EntityPlayerMP && !SumoPermissions.canEdit(p)) {
            long now = System.currentTimeMillis();
            if (now - lastDeniedMs > 2000) {
                lastDeniedMs = now;
                p.addChatMessage(new net.minecraft.util.ChatComponentText(net.minecraft.util.EnumChatFormatting.RED + SumoPermissions.DENIED));
            }
        }
    }

    private long lastDeniedMs;

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        SumoSession s = sumoWorld(event.world);
        if (s != null && !s.blockEditAllowed(event.getPlayer())) {
            event.setCanceled(true);
            deny(s, event.getPlayer());
        }
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.PlaceEvent event) {
        SumoSession s = sumoWorld(event.world);
        if (s != null && !s.blockEditAllowed(event.player)) {
            event.setCanceled(true);
            deny(s, event.player);
        }
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) return;
        SumoSession s = sumoWorld(event.world);
        if (s != null && !s.blockEditAllowed(event.entityPlayer)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onBucket(FillBucketEvent event) {
        SumoSession s = sumoWorld(event.world);
        if (s != null && !s.blockEditAllowed(event.entityPlayer)) event.setCanceled(true);
    }

    /** World-changing vanilla commands (with cheats or LAN) are the owner's only, in the Sumo world. */
    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        SumoSession s = session;
        if (s == null || event.command == null) return;
        String name = event.command.getCommandName();
        if (!BLOCKED_COMMANDS.contains(name)) return;
        if (event.sender.getEntityWorld() != s.world()) return;
        if (SumoPermissions.canEdit(event.sender)) return;
        event.setCanceled(true);
        event.sender.addChatMessage(new net.minecraft.util.ChatComponentText(net.minecraft.util.EnumChatFormatting.RED + SumoPermissions.DENIED));
    }

    private static final java.util.Set<String> BLOCKED_COMMANDS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "setblock", "fill", "clone", "gamemode", "defaultgamemode", "summon", "setworldspawn", "spreadplayers"));

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
