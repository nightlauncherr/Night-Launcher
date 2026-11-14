package com.kestrel.openmap;

import com.kestrel.sumo.DifficultyMode;
import com.kestrel.sumo.Playstyle;
import com.kestrel.sumo.SumoDifficulty;
import com.kestrel.sumo.SumoStats;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One Open Map PvP session, from loading the Open Map world until leaving it,
 * on the integrated server thread. States: LOBBY (no opponent), COUNTDOWN
 * (both at their starts with the kit), FIGHT, FINISHED (result shown; the next
 * round starts by itself), EDIT (owner only, creative). Every block placed or
 * broken from the countdown on is remembered and put back when the round
 * resets; only the owner's edit mode changes the map for good.
 */
final class OpenMapSession {
    enum Phase { WAITING, LOBBY, COUNTDOWN, FIGHT, FINISHED, EDIT, STOPPED }

    private static final int COUNTDOWN_TICKS = 60;
    private static final int NEXT_ROUND_TICKS = 80;
    /** A fight nobody wins in 5 minutes is a draw. */
    private static final int MAX_FIGHT_TICKS = 20 * 300;
    private static final String P = EnumChatFormatting.GREEN + "[Open Map] " + EnumChatFormatting.GRAY;

    private final WorldServer world;
    private final File dataDir;
    private final Logger log;
    private final Random rng = new Random();
    private PvpStats stats;
    private OpenMapPrefs prefs;
    private OpenMapLayout layout;
    private PvpBrain brain;
    private WorldTerrain terrain;
    private EntityPvpBot bot;
    private EntityPlayerMP player;
    private Phase phase = Phase.WAITING;
    private int timer;
    private int fightTicks;
    private int scorePlayer;
    private int scoreBot;
    private String lastResult = "";
    private long lastDurationMs;
    private int publishTimer;
    /** Original state of every block changed since the round started. */
    private final Map<BlockPos, IBlockState> journal = new LinkedHashMap<BlockPos, IBlockState>();

    // Per-round counts.
    private PvpProfile.Observation obs = new PvpProfile.Observation();
    private int roundBotHits, roundPlayerHits, roundBotRods, roundBotSnowballs, roundBotBlocks, roundPlayerBlocks;
    /** Playtest check of the first-contact reaction: tick the player was first in real reach, -1 until then. */
    private int reachTick = -1;
    private boolean firstSwingLogged;
    private int roundRodHits, roundSnowHits;
    private double roundDamageDealt, roundDamageTaken;
    // Watching the player (for learning).
    private double lastPx = Double.NaN, lastPy, lastPz;
    private boolean lastOnGround = true;
    private double lastDist;
    private int recentBotHits;
    private int recentDecay;
    private int sprintResetWindow;
    private boolean sprintDropped;

    // The kit is given on entering the world and refilled at the start of every round.
    private boolean playerKitGiven;
    private final ItemStack[] botHotbar = new ItemStack[4];
    private final ItemStack[] botArmor = new ItemStack[4];
    /** The player's PvP inventory while the owner is in edit mode (creative). */
    private ItemStack[] stashMain;
    private ItemStack[] stashArmor;

    private final PvpBrain.Self self = new PvpBrain.Self();
    private final PvpBrain.Target target = new PvpBrain.Target();

    OpenMapSession(WorldServer world, File dataDir, Logger log) {
        this.world = world;
        this.dataDir = dataDir;
        this.log = log;
    }

    // ---- Accessors (OpenMapView).

    Phase phase() {
        return phase;
    }

    PvpStats stats() {
        return stats;
    }

    OpenMapPrefs prefs() {
        return prefs;
    }

    OpenMapLayout layout() {
        return layout;
    }

    WorldServer world() {
        return world;
    }

    String lastResult() {
        return lastResult;
    }

    long lastDurationMs() {
        return lastDurationMs;
    }

    int scorePlayer() {
        return scorePlayer;
    }

    int scoreBot() {
        return scoreBot;
    }

    EntityPvpBot bot() {
        return bot;
    }

    boolean canEdit() {
        return player != null && OpenMapPermissions.canEdit(player);
    }

    boolean owns(EntityPvpBot e) {
        return e == bot && (phase == Phase.COUNTDOWN || phase == Phase.FIGHT || phase == Phase.FINISHED);
    }

    boolean isPlayer(Entity e) {
        return e != null && e == player;
    }

    boolean matchActive() {
        return phase == Phase.COUNTDOWN || phase == Phase.FIGHT || phase == Phase.FINISHED;
    }

    File statsFile() {
        return PvpStats.file(dataDir);
    }

    // ---- Start and stop.

    void start() {
        stats = PvpStats.load(statsFile());
        prefs = OpenMapPrefs.load(OpenMapPrefs.file(dataDir));
        OpenMapWorldSetup.applyRules(world);
        layout = OpenMapLayout.load(OpenMapLayout.file(dataDir));
        try {
            if (!OpenMapWorldSetup.isSetUp(world)) {
                // The world was just generated: lobby, trees and starts, once.
                layout = OpenMapWorldSetup.setUp(world);
                saveLayout();
            } else if (layout == null) {
                layout = new OpenMapLayout();
                BlockPos s = world.getSpawnPoint();
                layout.lobbySpawn = OpenMapWorldSetup.surface(world, s.getX(), s.getZ(), 0F);
                layout.playerSpawn = OpenMapWorldSetup.surface(world, s.getX() - 12, s.getZ() + 28, -90F);
                layout.aiSpawn = OpenMapWorldSetup.surface(world, s.getX() + 13, s.getZ() + 28, 90F);
                layout.centerX = world.getWorldBorder().getCenterX();
                layout.centerZ = world.getWorldBorder().getCenterZ();
                saveLayout();
            }
            File worldDir = world.getSaveHandler().getWorldDirectory();
            if (OpenMapWorldSetup.terrainVersion(worldDir) < OpenMapTerrain.VERSION && backedUp(worldDir)) {
                // One-time flattening of this world (a copy of the old one is set aside first).
                OpenMapTerrain.apply(world, layout, log);
                saveLayout();
                OpenMapWorldSetup.writeMarker(worldDir, OpenMapTerrain.VERSION);
            }
        } catch (IOException e) {
            log.error("[Kestrel] Open Map world setup failed", e);
        }
        terrain = new WorldTerrain(world);
        brain = new PvpBrain(new SumoDifficulty(skill()), stats.profile, terrain, rng);
        brain.setPlaystyle(prefs.style());
        log.info("[Kestrel] Open Map session started (matches {}, difficulty {}, {})", stats.matches,
                String.format("%.2f", stats.difficulty), prefs.style().label);
        publish();
    }

    /** A copy of the world from before the flattening exists (made now if needed); no copy, no change. */
    private boolean backedUp(File worldDir) {
        File[] old = worldDir.getParentFile().listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(OpenMapWorldSetup.WORLD_FOLDER + "-before-flatten-");
            }
        });
        if (old != null && old.length > 0) return true;
        if (OpenMapWorldSetup.isFresh(world)) return true; // just generated: nothing of the player's to keep
        return OpenMapWorlds.backup(worldDir, "before-flatten");
    }

    void stop(String reason) {
        if (phase == Phase.STOPPED) return;
        undoRound();
        removeBot();
        phase = Phase.STOPPED;
        saveStats();
        OpenMapView.clear();
        log.info("[Kestrel] Open Map session stopped ({}), session score {}-{}", reason, scorePlayer, scoreBot);
        player = null;
    }

    void onPlayerJoin(EntityPlayerMP p) {
        if (phase == Phase.STOPPED || p.worldObj != world) return;
        if (player != null && player != p) return;
        player = p;
        if (!playerKitGiven) {
            // Entering the world: the starting inventory (each round's start refills it).
            giveKit(p);
            playerKitGiven = true;
        }
        chat(P + "Welcome to Open Map PvP. Press " + EnumChatFormatting.WHITE + "M" + EnumChatFormatting.GRAY
                + " for the menu, or /openmap help.");
        enterLobby(true);
    }

    void onPlayerLeave(EntityPlayer p) {
        if (p == player) stop("player left");
    }

    // ---- State changes.

    /** Back to the lobby. The menu opens only on entering the world and on /stopopenmap. */
    void enterLobby(boolean openMenu) {
        undoRound();
        removeBot();
        phase = Phase.LOBBY;
        timer = 0;
        fightTicks = 0;
        reachTick = -1;
        firstSwingLogged = false;
        if (player == null) return;
        player.setGameType(WorldSettings.GameType.ADVENTURE);
        refresh(player);
        teleport(layout.lobbySpawn);
        clearTitle();
        publish();
        if (openMenu) OpenMapView.request();
    }

    /** START / next round. */
    boolean startMatch() {
        if (player == null) return false;
        if (phase == Phase.EDIT) {
            chat(P + "Leave edit mode first (/openmap edit off).");
            return false;
        }
        if (phase == Phase.COUNTDOWN || phase == Phase.FIGHT) {
            chat(P + "A match is already running. /stopopenmap ends it.");
            return false;
        }
        undoRound();
        removeBot();
        brain.setDifficulty(new SumoDifficulty(skill()));
        brain.setPlaystyle(prefs.style());
        brain.resetRound();
        player.setGameType(WorldSettings.GameType.SURVIVAL);
        refresh(player); // health, food and effects reset each round
        refillKit(player); // and the kit: full again, in the slots the player keeps things in
        spawnBot();
        placeAtStarts();
        resetRoundCounts();
        phase = Phase.COUNTDOWN;
        timer = COUNTDOWN_TICKS;
        player.playerNetServerHandler.sendPacket(new S45PacketTitle(0, 22, 4));
        title(EnumChatFormatting.GREEN + "3", scoreLine());
        publish();
        return true;
    }

    /** /stopopenmap: end the match now (not counted), back to the lobby; the menu opens once. */
    boolean stopMatch(boolean openMenu) {
        if (!matchActive()) return false;
        saveStats();
        chat(P + (phase == Phase.FINISHED ? "Stopped. Back to the lobby." : "Match stopped (not counted). Back to the lobby."));
        enterLobby(openMenu);
        return true;
    }

    // ---- Every server tick.

    void tick() {
        if (phase == Phase.STOPPED || phase == Phase.WAITING || player == null) return;
        if (player.isDead || player.worldObj != world) {
            stop("player gone");
            return;
        }
        OpenMapWorldSetup.keepClear(world);
        enforceGameMode();
        switch (phase) {
            case LOBBY:
                if (player.posY < layout.lobbySpawn.y - 30) teleport(layout.lobbySpawn);
                if (player.getFoodStats().getFoodLevel() < 20) player.getFoodStats().addStats(20, 20F);
                break;
            case COUNTDOWN:
                if (bot == null || bot.isDead) spawnBot();
                hold(layout.playerSpawn);
                if (--timer > 0 && timer % 20 == 0) title(EnumChatFormatting.GREEN.toString() + (timer / 20), null);
                if (timer <= 0) {
                    title(EnumChatFormatting.DARK_GREEN + "FIGHT!", null);
                    phase = Phase.FIGHT;
                    fightTicks = 0;
                    reachTick = -1;
                    firstSwingLogged = false;
                    publish();
                }
                break;
            case FIGHT:
                if (bot == null || bot.isDead) {
                    endMatch(SumoStats.Result.WIN); // it is gone: count it as the player's
                    break;
                }
                fightTicks++;
                observe();
                if (fightTicks >= MAX_FIGHT_TICKS) endMatch(SumoStats.Result.DRAW);
                break;
            case FINISHED:
                if (--timer <= 0 && !startMatch()) enterLobby(false);
                break;
            default:
                break;
        }
        if (++publishTimer >= 10) {
            publishTimer = 0;
            publish();
        }
    }

    /** Adventure outside fights (no building in the lobby), survival in fights, creative only in the owner's edit mode. */
    private void enforceGameMode() {
        WorldSettings.GameType want;
        if (phase == Phase.EDIT) {
            if (!canEdit()) {
                chat(EnumChatFormatting.RED + OpenMapPermissions.DENIED);
                enterLobby(false);
                return;
            }
            want = WorldSettings.GameType.CREATIVE;
        } else if (phase == Phase.FIGHT || phase == Phase.COUNTDOWN || phase == Phase.FINISHED) {
            want = WorldSettings.GameType.SURVIVAL;
        } else {
            want = WorldSettings.GameType.ADVENTURE;
        }
        if (player.theItemInWorldManager.getGameType() != want) player.setGameType(want);
    }

    /** The opponent's AI step, from its own entity tick. */
    void driveBot(EntityPvpBot e) {
        if (e != bot || player == null) return;
        if (phase != Phase.FIGHT) {
            if (phase == Phase.COUNTDOWN) {
                OpenMapLayout.Spot s = layout.aiSpawn;
                e.setPosition(s.x, s.y, s.z);
                e.motionX = 0;
                e.motionZ = 0;
            }
            e.idle((float) PvpBrain.yawTo(e.posX, e.posZ, player.posX, player.posZ));
            return;
        }
        self.x = e.posX;
        self.y = e.posY;
        self.z = e.posZ;
        self.mx = e.motionX;
        self.my = e.motionY;
        self.mz = e.motionZ;
        self.yaw = e.rotationYaw;
        self.pitch = e.rotationPitch;
        self.onGround = e.onGround;
        self.collided = e.isCollidedHorizontally;
        self.sprinting = e.isSprinting();
        self.health = e.getHealth();
        self.fallDistance = e.fallDistance;
        self.planks = e.count(EntityPvpBot.PLANKS);
        self.snowballs = e.count(EntityPvpBot.SNOWBALL);
        self.hasRod = e.hotbar[EntityPvpBot.ROD] != null;
        self.hasSword = e.hotbar[EntityPvpBot.SWORD] != null;
        self.hookOut = e.hook != null && !e.hook.isDead;
        self.hookHooked = self.hookOut && e.hook.hooked();
        self.hookStopped = self.hookOut && e.hook.inGround;
        self.hookAge = self.hookOut ? e.hook.ticksInAir : 0;
        self.slot = PvpBrain.Slot.values()[e.selectedSlot()];
        target.x = player.posX;
        target.y = player.posY;
        target.z = player.posZ;
        target.yaw = player.rotationYaw;
        target.onGround = player.onGround;
        target.sprinting = player.isSprinting();
        target.visible = e.canEntityBeSeen(player);
        target.hurtTime = player.hurtTime;
        target.id = player.getEntityId();
        warnOfProjectiles(e);
        PvpBrain.Controls c = brain.tick(self, target);
        if (!firstSwingLogged) {
            double dx = player.posX - e.posX;
            double dz = player.posZ - e.posZ;
            if (reachTick < 0 && dx * dx + dz * dz <= PvpBrain.REACH * PvpBrain.REACH) reachTick = fightTicks;
            if (c.attack) {
                firstSwingLogged = true;
                if (reachTick >= 0) {
                    log.info("[Kestrel] Open Map: first swing {} ms after the player came into reach", (fightTicks - reachTick) * 50);
                } else {
                    log.info("[Kestrel] Open Map: first swing before the player was in reach");
                }
            }
        }
        e.apply(c, player);
        if (e.lastPlaceResult != 0) brain.onPlaceResult(e.lastPlaceResult > 0);
    }

    /** The player's snowballs and rod hook in flight near the opponent (it can see them coming). */
    private void warnOfProjectiles(EntityPvpBot e) {
        AxisAlignedBB near = e.getEntityBoundingBox().expand(14, 6, 14);
        for (Entity p : world.getEntitiesWithinAABB(Entity.class, near)) {
            boolean thrown = p instanceof EntitySnowball && !(p instanceof BotSnowball)
                    && ((EntitySnowball) p).getThrower() == player;
            boolean hook = p instanceof EntityFishHook && ((EntityFishHook) p).angler == player
                    && ((EntityFishHook) p).caughtEntity == null;
            if ((thrown || hook) && !p.isDead) brain.incoming(p.posX, p.posY, p.posZ, p.motionX, p.motionY, p.motionZ);
        }
    }

    // ---- Damage and deaths.

    /**
     * Someone would die. Returns true to cancel the vanilla death: in a fight
     * the round ends instead (no death screen, nothing dropped).
     */
    boolean onDeath(EntityLivingBase victim) {
        if (victim == player) {
            player.setHealth(player.getMaxHealth());
            if (phase == Phase.FIGHT) endMatch(SumoStats.Result.LOSS);
            else teleport(phase == Phase.LOBBY ? layout.lobbySpawn : layout.playerSpawn);
            return true;
        }
        if (victim == bot) {
            bot.setHealth(bot.getMaxHealth());
            if (phase == Phase.FIGHT) endMatch(SumoStats.Result.WIN);
            return true;
        }
        return false;
    }

    /** Damage after armor, for statistics. */
    void onHurt(EntityLivingBase victim, Entity attacker, float amount) {
        if (phase != Phase.FIGHT) return;
        if (victim == bot && attacker == player) {
            roundPlayerHits++;
            roundDamageDealt += amount;
            brain.onHitTaken();
            if (player.isSprinting()) {
                obs.sprintHits++;
                sprintResetWindow = 12;
                sprintDropped = false;
            }
        } else if (victim == player && attacker == bot) {
            roundDamageTaken += amount;
        }
    }

    void onBotHit(boolean sprintHit, float damage, boolean crit) {
        roundBotHits++;
        recentBotHits++;
        brain.onHitLanded(sprintHit, damage);
    }

    void onBotRodCast() {
        roundBotRods++;
    }

    void onBotSnowball() {
        roundBotSnowballs++;
    }

    void onBotPlaced() {
        roundBotBlocks++;
    }

    void onBotRodHit() {
        roundRodHits++;
    }

    void onBotSnowballHit() {
        roundSnowHits++;
    }

    /** The player threw a snowball or cast a rod (for learning). */
    void onPlayerProjectile(Entity e) {
        if (phase != Phase.FIGHT) return;
        if (e instanceof EntityFishHook) obs.rodCasts++;
        else if (e instanceof EntitySnowball) obs.snowballs++;
    }

    void onPlayerAteApple() {
        if (phase == Phase.FIGHT) brain.onTargetHealed();
    }

    // ---- Blocks.

    /** Whether the player may place or break here now; fight changes are remembered to undo. */
    boolean allowBlockChange(EntityPlayer p, BlockPos pos, IBlockState original, boolean placing) {
        if (p != player) return false;
        if (phase == Phase.EDIT) return canEdit();
        if (phase == Phase.FIGHT || phase == Phase.FINISHED) {
            recordChange(pos, original);
            if (placing) {
                roundPlayerBlocks++;
                obs.blocksPlaced++;
            }
            return true;
        }
        return false; // lobby and countdown: no building
    }

    void recordChange(BlockPos pos, IBlockState original) {
        if (phase == Phase.EDIT || phase == Phase.LOBBY) return;
        BlockPos key = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        if (!journal.containsKey(key)) journal.put(key, original);
    }

    /** Put every block changed this round back, and clear dropped items and projectiles. */
    private void undoRound() {
        if (!journal.isEmpty()) {
            List<Map.Entry<BlockPos, IBlockState>> entries = new ArrayList<Map.Entry<BlockPos, IBlockState>>(journal.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<BlockPos, IBlockState> en = entries.get(i);
                if (world.getBlockState(en.getKey()) != en.getValue()) world.setBlockState(en.getKey(), en.getValue(), 3);
            }
            journal.clear();
        }
        for (Object o : new ArrayList<Object>(world.loadedEntityList)) {
            Entity e = (Entity) o;
            if (e instanceof EntityItem || e instanceof EntityXPOrb || e instanceof EntityArrow || e instanceof EntitySnowball
                    || e instanceof EntityFishHook || e instanceof BotFishHook) {
                e.setDead();
            }
        }
    }

    // ---- Edit mode and map settings (owner only; checked here as well as in the command).

    private boolean requireEdit() {
        if (canEdit()) return true;
        chat(EnumChatFormatting.RED + OpenMapPermissions.DENIED);
        return false;
    }

    void setEdit(boolean on) {
        if (player == null || !requireEdit()) return;
        if (on) {
            if (phase == Phase.EDIT) {
                chat(P + "Edit mode is already on.");
                return;
            }
            undoRound();
            removeBot();
            stashInventory();
            phase = Phase.EDIT;
            player.setGameType(WorldSettings.GameType.CREATIVE);
            clearTitle();
            chat(P + EnumChatFormatting.YELLOW + "Edit mode on" + EnumChatFormatting.GRAY
                    + ": build and break freely; these changes are permanent. /openmap setspawn player|ai|lobby, "
                    + "/openmap save, /openmap edit off.");
            publish();
        } else {
            if (phase != Phase.EDIT) {
                chat(P + "Edit mode isn't on.");
                return;
            }
            restoreInventory();
            saveWorld();
            chat(P + "Edit mode off. Your changes are saved with the world.");
            enterLobby(false);
        }
    }

    void setSpawn(String which) {
        if (player == null || !requireEdit()) return;
        if (matchActive()) {
            chat(P + "Stop the match first (/stopopenmap).");
            return;
        }
        OpenMapLayout.Spot s = new OpenMapLayout.Spot(player.posX, player.posY, player.posZ, player.rotationYaw);
        if (!s.valid() || !world.getWorldBorder().contains(new BlockPos(s.x, s.y, s.z))) {
            chat(EnumChatFormatting.RED + "[Open Map] That position can't be used (outside the map?).");
            return;
        }
        String label;
        if ("player".equals(which)) {
            layout.playerSpawn = s;
            label = "Player start";
        } else if ("ai".equals(which) || "bot".equals(which)) {
            layout.aiSpawn = s;
            label = "AI start";
        } else {
            layout.lobbySpawn = s;
            label = "Lobby spawn";
        }
        saveLayout();
        chat(P + label + " set to " + s + ".");
        publish();
    }

    /** Write the world and start positions to disk now (they are also saved on leaving). */
    void save() {
        if (!requireEdit()) return;
        saveLayout();
        boolean ok = saveWorld();
        chat(ok ? P + EnumChatFormatting.GREEN + "Map saved." : EnumChatFormatting.RED + "[Open Map] Saving the world failed (see the log).");
    }

    /**
     * Reset the map to freshly generated terrain. The world can't be replaced
     * while it is open, so this marks it; the next time Open Map is opened the
     * current world is moved aside (kept as a backup) and a new one is made.
     */
    void requestReset() {
        if (!requireEdit()) return;
        try {
            File flag = OpenMapWorlds.resetFlag(dataDir);
            if (!flag.getParentFile().isDirectory() && !flag.getParentFile().mkdirs()) throw new IOException("cannot create folder");
            Files.write(flag.toPath(), "reset requested".getBytes(StandardCharsets.UTF_8));
            chat(P + EnumChatFormatting.YELLOW + "Map reset scheduled." + EnumChatFormatting.GRAY
                    + " Leave the world (Save and Quit) and open Open Map again: the current map is kept as a backup "
                    + "and a fresh one is generated.");
        } catch (IOException e) {
            chat(EnumChatFormatting.RED + "[Open Map] Could not schedule the reset: " + e.getMessage());
        }
    }

    // ---- Opponent settings.

    void setMode(DifficultyMode m) {
        prefs.difficulty = m.name();
        savePrefs();
        chat(P + "Difficulty: " + m.label + (m.adaptive() ? " (follows your results)" : " (fixed; adaptive progress is kept)"));
        publish();
    }

    void setStyle(Playstyle s) {
        prefs.playstyle = s.name();
        brain.setPlaystyle(s);
        savePrefs();
        chat(P + "Playstyle: " + s.label);
        publish();
    }

    void resetStats() throws IOException {
        File f = statsFile();
        if (f.isFile()) Files.copy(f.toPath(), new File(f.getParentFile(), f.getName() + ".before-reset-" + System.currentTimeMillis()).toPath());
        stats = new PvpStats();
        stats.save(f);
        brain = new PvpBrain(new SumoDifficulty(skill()), stats.profile, terrain, rng);
        brain.setPlaystyle(prefs.style());
        scorePlayer = 0;
        scoreBot = 0;
        if (matchActive()) enterLobby(false);
        publish();
    }

    private double skill() {
        return prefs.mode().skill(stats.difficulty);
    }

    // ---- Rounds.

    private void endMatch(SumoStats.Result result) {
        long ms = fightTicks * 50L;
        if (result == SumoStats.Result.WIN) scorePlayer++;
        else if (result == SumoStats.Result.LOSS) scoreBot++;
        obs.ticks = fightTicks;
        stats.profile.learn(obs);
        stats.record(result, ms, prefs.mode().adaptive());
        stats.hitsLanded += roundPlayerHits;
        stats.hitsTaken += roundBotHits;
        stats.damageDealt += roundDamageDealt;
        stats.damageTaken += roundDamageTaken;
        stats.botRodHits += roundRodHits;
        stats.botSnowballHits += roundSnowHits;
        stats.botBlocksPlaced += roundBotBlocks;
        stats.playerBlocksPlaced += roundPlayerBlocks;
        saveStats();
        String head = result == SumoStats.Result.WIN ? EnumChatFormatting.GREEN + "You win!"
                : result == SumoStats.Result.LOSS ? EnumChatFormatting.RED + "You lose!" : EnumChatFormatting.YELLOW + "Draw";
        lastResult = head;
        lastDurationMs = ms;
        title(head, scoreLine());
        chat(head + EnumChatFormatting.GRAY + String.format(" (%.1fs; hits %d-%d; its rod %d/%d, snowballs %d/%d, blocks %d) ",
                ms / 1000.0, roundPlayerHits, roundBotHits, roundRodHits, roundBotRods, roundSnowHits, roundBotSnowballs, roundBotBlocks)
                + overallLine());
        chat(EnumChatFormatting.DARK_GRAY + "Next round in " + (NEXT_ROUND_TICKS / 20) + "s. /stopopenmap or M to stop.");
        phase = Phase.FINISHED;
        timer = NEXT_ROUND_TICKS;
        if (bot != null) bot.removeHook();
        player.setHealth(player.getMaxHealth());
        if (bot != null) bot.setHealth(bot.getMaxHealth());
        publish();
    }

    private void resetRoundCounts() {
        obs = new PvpProfile.Observation();
        roundBotHits = roundPlayerHits = roundBotRods = roundBotSnowballs = roundBotBlocks = roundPlayerBlocks = 0;
        reachTick = -1;
        firstSwingLogged = false;
        roundRodHits = roundSnowHits = 0;
        roundDamageDealt = roundDamageTaken = 0;
        lastPx = Double.NaN;
        recentBotHits = 0;
        sprintResetWindow = 0;
    }

    /** Watch the player's habits during a fight (only what the opponent could see). */
    private void observe() {
        double px = player.posX;
        double py = player.posY;
        double pz = player.posZ;
        double dist = bot == null ? 99 : player.getDistanceToEntity(bot);
        if (!Double.isNaN(lastPx)) {
            double vx = px - lastPx;
            double vz = pz - lastPz;
            if (dist < 4) {
                obs.closeTicks++;
                if (lastOnGround && !player.onGround && py > lastPy + 0.2) obs.closeJumps++;
                double yaw = Math.toRadians(player.rotationYaw);
                double left = vx * Math.cos(yaw) + vz * Math.sin(yaw);
                if (Math.abs(left) > 0.05) {
                    obs.strafeTicks++;
                    if (left > 0) obs.strafeLeftTicks++;
                }
            } else if (dist < 12) {
                obs.midTicks++;
                if (dist < lastDist - 0.05) obs.approachTicks++;
            }
            if (recentBotHits >= 2) {
                obs.pressuredTicks++;
                if (dist > lastDist + 0.05) obs.retreatTicks++;
            }
        }
        if (++recentDecay >= 40) {
            recentDecay = 0;
            recentBotHits = 0;
        }
        if (sprintResetWindow > 0) {
            sprintResetWindow--;
            if (!player.isSprinting()) sprintDropped = true;
            else if (sprintDropped) {
                obs.sprintResets++;
                sprintResetWindow = 0;
            }
        }
        lastPx = px;
        lastPy = py;
        lastPz = pz;
        lastOnGround = player.onGround;
        lastDist = dist;
    }

    // ---- Helpers.

    /**
     * The player's starting inventory when entering the world: diamond armor;
     * hotbar iron sword, 64 oak planks, fishing rod, 32 snowballs, 3 golden
     * apples; nothing else.
     */
    static void giveKit(EntityPlayerMP p) {
        clearInventory(p);
        InventoryPlayer inv = p.inventory;
        inv.mainInventory[0] = new ItemStack(Items.iron_sword);
        inv.mainInventory[1] = new ItemStack(Blocks.planks, 64, 0);
        inv.mainInventory[2] = new ItemStack(Items.fishing_rod);
        inv.mainInventory[3] = new ItemStack(Items.snowball, 32);
        inv.mainInventory[4] = new ItemStack(Items.golden_apple, 3, 0);
        inv.armorInventory[0] = new ItemStack(Items.diamond_boots);
        inv.armorInventory[1] = new ItemStack(Items.diamond_leggings);
        inv.armorInventory[2] = new ItemStack(Items.diamond_chestplate);
        inv.armorInventory[3] = new ItemStack(Items.diamond_helmet);
        inv.currentItem = 0;
        p.inventoryContainer.detectAndSendChanges();
    }

    /** The kit's hotbar items in their default slots (0-4). */
    private static ItemStack[] kitItems() {
        return new ItemStack[]{new ItemStack(Items.iron_sword), new ItemStack(Blocks.planks, 64, 0),
                new ItemStack(Items.fishing_rod), new ItemStack(Items.snowball, 32), new ItemStack(Items.golden_apple, 3, 0)};
    }

    /**
     * Start of a round: the full kit again (new sword, rod and armor, 64 planks,
     * 32 snowballs, 3 golden apples). Each item goes back to the slot where the
     * player has it now, so a custom hotbar layout stays; an item that's gone
     * (used up) returns to its default slot, or the first free one. Anything
     * that isn't part of the kit is removed.
     */
    static void refillKit(EntityPlayerMP p) {
        InventoryPlayer inv = p.inventory;
        refillMain(inv.mainInventory);
        inv.setItemStack(null);
        inv.armorInventory[0] = new ItemStack(Items.diamond_boots);
        inv.armorInventory[1] = new ItemStack(Items.diamond_leggings);
        inv.armorInventory[2] = new ItemStack(Items.diamond_chestplate);
        inv.armorInventory[3] = new ItemStack(Items.diamond_helmet);
        p.inventoryContainer.detectAndSendChanges();
    }

    /** The main inventory part of {@link #refillKit}: full kit, each item in its current slot. */
    static void refillMain(ItemStack[] main) {
        ItemStack[] kit = kitItems();
        int[] slot = new int[kit.length];
        for (int k = 0; k < kit.length; k++) {
            slot[k] = -1;
            for (int i = 0; i < main.length && slot[k] < 0; i++) {
                if (main[i] != null && main[i].getItem() == kit[k].getItem()) slot[k] = i;
            }
        }
        for (int i = 0; i < main.length; i++) main[i] = null;
        for (int k = 0; k < kit.length; k++) {
            if (slot[k] >= 0) main[slot[k]] = kit[k];
        }
        for (int k = 0; k < kit.length; k++) {
            if (slot[k] >= 0) continue;
            int at = main[k] == null ? k : -1;
            for (int i = 0; at < 0 && i < main.length; i++) if (main[i] == null) at = i;
            if (at >= 0) main[at] = kit[k];
        }
    }

    /** Keep the PvP inventory aside during edit mode (creative), exactly as it was. */
    private void stashInventory() {
        InventoryPlayer inv = player.inventory;
        stashMain = new ItemStack[inv.mainInventory.length];
        stashArmor = new ItemStack[inv.armorInventory.length];
        for (int i = 0; i < stashMain.length; i++) stashMain[i] = inv.mainInventory[i] == null ? null : inv.mainInventory[i].copy();
        for (int i = 0; i < stashArmor.length; i++) stashArmor[i] = inv.armorInventory[i] == null ? null : inv.armorInventory[i].copy();
        clearInventory(player);
    }

    private void restoreInventory() {
        if (stashMain == null) return;
        InventoryPlayer inv = player.inventory;
        clearInventory(player);
        System.arraycopy(stashMain, 0, inv.mainInventory, 0, Math.min(stashMain.length, inv.mainInventory.length));
        System.arraycopy(stashArmor, 0, inv.armorInventory, 0, Math.min(stashArmor.length, inv.armorInventory.length));
        stashMain = null;
        stashArmor = null;
        player.inventoryContainer.detectAndSendChanges();
    }

    static void clearInventory(EntityPlayerMP p) {
        InventoryPlayer inv = p.inventory;
        for (int i = 0; i < inv.mainInventory.length; i++) inv.mainInventory[i] = null;
        for (int i = 0; i < inv.armorInventory.length; i++) inv.armorInventory[i] = null;
        inv.setItemStack(null);
        p.inventoryContainer.detectAndSendChanges();
    }

    /** Full health and food, no effects, not burning. */
    private static void refresh(EntityPlayerMP p) {
        p.clearActivePotions();
        p.extinguish();
        p.fallDistance = 0;
        p.setHealth(p.getMaxHealth());
        p.getFoodStats().addStats(20, 20F);
    }

    private void spawnBot() {
        if (bot != null && !bot.isDead) return;
        EntityPvpBot.fillKit(botHotbar, botArmor); // a full kit every round, like the player's (never apples)
        bot = new EntityPvpBot(world, this, botHotbar, botArmor);
        bot.setHealth(bot.getMaxHealth());
        OpenMapLayout.Spot b = layout.aiSpawn;
        bot.setLocationAndAngles(b.x, b.y, b.z, b.yaw, 0F);
        bot.rotationYawHead = b.yaw;
        world.spawnEntityInWorld(bot);
    }

    private void removeBot() {
        if (bot != null) {
            bot.removeHook();
            bot.setDead();
            bot = null;
        }
        for (Object o : world.loadedEntityList) {
            if (o instanceof EntityPvpBot || o instanceof BotFishHook) ((Entity) o).setDead();
        }
    }

    private void placeAtStarts() {
        teleport(layout.playerSpawn);
        if (bot != null) {
            OpenMapLayout.Spot b = layout.aiSpawn;
            bot.setLocationAndAngles(b.x, b.y, b.z, b.yaw, 0F);
            bot.rotationYawHead = b.yaw;
            bot.motionX = bot.motionY = bot.motionZ = 0;
            bot.fallDistance = 0;
        }
    }

    private void teleport(OpenMapLayout.Spot s) {
        if (player == null || s == null) return;
        player.fallDistance = 0;
        player.playerNetServerHandler.setPlayerLocation(s.x, s.y, s.z, s.yaw, 0F);
    }

    private void hold(OpenMapLayout.Spot s) {
        double dx = player.posX - s.x;
        double dz = player.posZ - s.z;
        if (dx * dx + dz * dz > 0.01 || Math.abs(player.posY - s.y) > 0.6) {
            player.fallDistance = 0;
            player.playerNetServerHandler.setPlayerLocation(s.x, s.y, s.z, player.rotationYaw, player.rotationPitch);
        }
    }

    private boolean saveWorld() {
        try {
            if (world.getMinecraftServer() != null) world.getMinecraftServer().getConfigurationManager().saveAllPlayerData();
            world.saveAllChunks(true, null);
            return true;
        } catch (MinecraftException e) {
            log.error("[Kestrel] cannot save the Open Map world", e);
            return false;
        }
    }

    private void publish() {
        if (phase == Phase.STOPPED || stats == null || layout == null) return;
        OpenMapView.publish(new OpenMapView(this));
    }

    private void saveStats() {
        if (stats == null) return;
        try {
            stats.save(statsFile());
        } catch (IOException e) {
            log.error("[Kestrel] cannot save Open Map statistics", e);
        }
    }

    private void saveLayout() {
        if (layout == null) return;
        try {
            layout.save(OpenMapLayout.file(dataDir));
        } catch (IOException e) {
            log.error("[Kestrel] cannot save the Open Map layout", e);
        }
    }

    private void savePrefs() {
        try {
            prefs.save(OpenMapPrefs.file(dataDir));
        } catch (IOException e) {
            log.error("[Kestrel] cannot save the Open Map opponent settings", e);
        }
    }

    String scoreLine() {
        return EnumChatFormatting.WHITE + "You " + scorePlayer + EnumChatFormatting.GRAY + " - "
                + EnumChatFormatting.WHITE + scoreBot + " " + EntityPvpBot.NAME;
    }

    String overallLine() {
        return EnumChatFormatting.DARK_GRAY + String.format("Overall %d-%d (%d%%), streak %d, difficulty %d%%",
                stats.wins, stats.losses, stats.winRatePercent(), stats.winStreak(), (int) Math.round(skill() * 100));
    }

    private void title(String title, String subtitle) {
        if (player == null) return;
        if (subtitle != null) {
            player.playerNetServerHandler.sendPacket(new S45PacketTitle(S45PacketTitle.Type.SUBTITLE, new ChatComponentText(subtitle)));
        }
        player.playerNetServerHandler.sendPacket(new S45PacketTitle(S45PacketTitle.Type.TITLE, new ChatComponentText(title)));
    }

    private void clearTitle() {
        if (player == null) return;
        player.playerNetServerHandler.sendPacket(new S45PacketTitle(S45PacketTitle.Type.CLEAR, null));
    }

    void chat(String text) {
        if (player == null) return;
        player.playerNetServerHandler.sendPacket(new S02PacketChat(new ChatComponentText(text), (byte) 0));
    }
}
