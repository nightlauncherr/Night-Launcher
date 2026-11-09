package com.kestrel.openmap;

import com.kestrel.KestrelMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.init.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerUseItemEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Starts and stops Open Map sessions with the integrated server. A session
 * only exists while the dedicated Open Map world is loaded; every other world
 * (including Sumo's) is untouched by everything here.
 */
public final class OpenMapServer {
    private static final OpenMapServer INSTANCE = new OpenMapServer();
    /** World-changing cheat commands: the owner's only, in the Open Map world. */
    private static final Set<String> BLOCKED_COMMANDS = new HashSet<String>(Arrays.asList(
            "setblock", "fill", "clone", "gamemode", "defaultgamemode", "summon", "setworldspawn", "spreadplayers",
            "give", "clear", "worldborder", "kill", "effect", "enchant", "xp", "tp"));

    private final File dataDir;
    private OpenMapSession session;

    private OpenMapServer() {
        dataDir = KestrelMod.client() != null ? KestrelMod.client().dataDir()
                : new File(net.minecraft.client.Minecraft.getMinecraft().mcDataDir, "kestrel");
    }

    public static OpenMapServer get() {
        return INSTANCE;
    }

    OpenMapSession session() {
        return session;
    }

    File dataDir() {
        return dataDir;
    }

    public void serverStarting(FMLServerStartingEvent event) {
        stopSession("new server");
        MinecraftServer server = event.getServer();
        if (!OpenMapWorldSetup.WORLD_FOLDER.equals(server.getFolderName())) return;
        WorldServer overworld = server.worldServerForDimension(0);
        if (overworld == null) return;
        session = new OpenMapSession(overworld, dataDir, KestrelMod.LOG);
        session.start();
        event.registerServerCommand(new OpenMapCommand(this));
        event.registerServerCommand(new StopOpenMapCommand(this));
    }

    public void serverStopping() {
        stopSession("server stopping");
    }

    private void stopSession(String reason) {
        if (session != null) {
            session.stop(reason);
            session = null;
        }
    }

    private OpenMapSession in(World w) {
        OpenMapSession s = session;
        return s != null && w != null && !w.isRemote && w == s.world() ? s : null;
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
        if (session != null) session.onPlayerLeave(event.player);
    }

    /** A round ends on the first death; there is no vanilla death (no death screen, nothing dropped). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event) {
        OpenMapSession s = in(event.entityLiving.worldObj);
        if (s != null && s.onDeath(event.entityLiving)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onHurt(LivingHurtEvent event) {
        OpenMapSession s = in(event.entityLiving.worldObj);
        if (s != null && !event.isCanceled()) s.onHurt(event.entityLiving, event.source.getEntity(), event.ammount);
    }

    // ---- Blocks: fights may build and break (undone each round); the lobby can't be changed;
    // edit mode only for the owner.

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        OpenMapSession s = in(event.world);
        if (s == null) return;
        if (!s.allowBlockChange(event.getPlayer(), event.pos, event.state, false)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.PlaceEvent event) {
        OpenMapSession s = in(event.world);
        if (s == null) return;
        if (!s.allowBlockChange(event.player, event.pos, event.blockSnapshot.getReplacedBlock(), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onBucket(FillBucketEvent event) {
        OpenMapSession s = in(event.world);
        if (s != null && s.phase() != OpenMapSession.Phase.EDIT) event.setCanceled(true);
    }

    /** No animals or mobs in the practice world; count the player's rods and snowballs for learning. */
    @SubscribeEvent
    public void onJoinWorld(EntityJoinWorldEvent event) {
        OpenMapSession s = in(event.world);
        if (s == null) return;
        Entity e = event.entity;
        if (e instanceof EntityAnimal || e instanceof IMob || e instanceof EntityWaterMob || e instanceof EntityAmbientCreature) {
            event.setCanceled(true);
            return;
        }
        if (e instanceof EntityFishHook && ((EntityFishHook) e).angler != null && s.isPlayer(((EntityFishHook) e).angler)) {
            s.onPlayerProjectile(e);
        } else if (e instanceof EntitySnowball && !(e instanceof BotSnowball) && s.isPlayer(((EntitySnowball) e).getThrower())) {
            s.onPlayerProjectile(e);
        }
    }

    @SubscribeEvent
    public void onUseFinish(PlayerUseItemEvent.Finish event) {
        OpenMapSession s = in(event.entityPlayer.worldObj);
        if (s != null && event.item != null && event.item.getItem() == Items.golden_apple && s.isPlayer(event.entityPlayer)) {
            s.onPlayerAteApple();
        }
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        OpenMapSession s = session;
        if (s == null || event.command == null) return;
        if (!BLOCKED_COMMANDS.contains(event.command.getCommandName())) return;
        if (event.sender.getEntityWorld() != s.world()) return;
        if (OpenMapPermissions.canEdit(event.sender)) return;
        event.setCanceled(true);
        event.sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + OpenMapPermissions.DENIED));
    }

    void onBotRodHit(EntityPvpBot bot) {
        if (session != null && session.bot() == bot) session.onBotRodHit();
    }

    void onBotSnowballHit(EntityPvpBot bot) {
        if (session != null && session.bot() == bot) session.onBotSnowballHit();
    }
}
