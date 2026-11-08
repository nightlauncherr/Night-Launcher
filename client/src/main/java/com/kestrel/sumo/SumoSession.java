package com.kestrel.sumo;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One Sumo practice session, from loading the Sumo world until leaving it, on
 * the integrated server thread. States:
 * <ul>
 *   <li>LOBBY: no opponent exists, no timers; the menu offers START SUMO.</li>
 *   <li>COUNTDOWN: both placed at their starts; the opponent can't act yet.</li>
 *   <li>FIGHT: the opponent is fully active; timer and scoring run.</li>
 *   <li>FINISHED: result recorded and shown; REMATCH or LOBBY.</li>
 *   <li>EDIT: no match, no opponent, creative mode to build.</li>
 * </ul>
 * Every transition goes through {@link #removeBot()} and resets the timers,
 * so nothing is left running between states.
 */
final class SumoSession {
    enum Phase { WAITING, LOBBY, COUNTDOWN, FIGHT, FINISHED, EDIT, STOPPED }

    private static final int COUNTDOWN_TICKS = 60;
    /** A match nobody wins in 3 minutes is a draw. */
    private static final int MAX_FIGHT_TICKS = 20 * 180;
    /** Pause on the result before the next round starts by itself. */
    private static final int NEXT_ROUND_TICKS = 60;
    private static final String P = EnumChatFormatting.LIGHT_PURPLE + "[Sumo] " + EnumChatFormatting.GRAY;

    private final WorldServer world;
    private final File dataDir;
    private final Logger log;
    private final Random rng = new Random();
    private SumoStats stats;
    private SumoPrefs prefs;
    private ArenaLayout layout;
    private SumoBrain brain;
    private EntitySumoBot bot;
    private EntityPlayerMP player;
    private Phase phase = Phase.WAITING;
    private int timer;
    private int fightTicks;
    private int scorePlayer;
    private int scoreBot;
    private int hitsByPlayer;
    private int hitsByBot;
    private String lastResult = "";
    private long lastDurationMs;
    private int publishTimer;
    private final SumoBrain.Body selfBody = new SumoBrain.Body();
    private final SumoBrain.Body targetBody = new SumoBrain.Body();

    SumoSession(WorldServer world, File dataDir, Logger log) {
        this.world = world;
        this.dataDir = dataDir;
        this.log = log;
    }

    // ---- Accessors (also read by SumoView).

    Phase phase() {
        return phase;
    }

    SumoStats stats() {
        return stats;
    }

    SumoPrefs prefs() {
        return prefs;
    }

    ArenaLayout layout() {
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

    boolean hasSavedArena() {
        return ArenaLayout.snapshot(dataDir).isFile();
    }

    File statsFile() {
        return SumoStats.file(dataDir);
    }

    boolean owns(EntitySumoBot e) {
        return e == bot && (phase == Phase.COUNTDOWN || phase == Phase.FIGHT || phase == Phase.FINISHED);
    }

    boolean isCombatant(Entity e) {
        return e != null && (e == bot || e == player);
    }

    // ---- Start and stop.

    void start() {
        stats = SumoStats.load(statsFile());
        prefs = SumoPrefs.load(SumoPrefs.file(dataDir));
        SumoWorldSetup.applyRules(world);
        // The blocks are never generated or rebuilt on load: the world is the official
        // map (installed by OfficialMap before it opened), as the player left it.
        layout = ArenaLayout.load(ArenaLayout.file(dataDir));
        if (layout == null) {
            layout = OfficialMap.layout();
            saveLayout();
        }
        keepOfficialSnapshot();
        brain = new SumoBrain(new SumoDifficulty(skill()), stats.profile, rng);
        brain.setPlaystyle(prefs.style());
        log.info("[Kestrel] Sumo session started (matches {}, difficulty {}, {})", stats.matches,
                String.format("%.2f", stats.difficulty), prefs.style().label);
        publish();
    }

    void stop(String reason) {
        if (phase == Phase.STOPPED) return;
        Phase was = phase;
        removeBot();
        brain.resetRound(); // closes the strafe observation in progress
        phase = Phase.STOPPED;
        saveStats();
        saveLayout();
        SumoView.clear();
        log.info("[Kestrel] Sumo session stopped ({}; was {}), session score {}-{}", reason, was, scorePlayer, scoreBot);
        player = null;
    }

    void onPlayerJoin(EntityPlayerMP p) {
        if (phase == Phase.STOPPED || p.worldObj != world) return;
        if (player != null && player != p) return;
        player = p;
        removeStrays();
        chat(P + "Welcome to the Sumo practice lobby. Press " + EnumChatFormatting.WHITE + "M"
                + EnumChatFormatting.GRAY + " for the Sumo menu, or /sumo help.");
        enterLobby(true);
    }

    void onPlayerLeave(EntityPlayer p) {
        if (p == player) stop("player left");
    }

    // ---- State changes.

    /** Whether this player may edit the Sumo world (checked by UUID on every edit action). */
    boolean canEdit() {
        return player != null && SumoPermissions.canEdit(player);
    }

    /** Whether {@code p} may change blocks right now: the owner, in edit mode. */
    boolean blockEditAllowed(EntityPlayer p) {
        return phase == Phase.EDIT && p != null && p == player && SumoPermissions.canEdit(p);
    }

    /** Refuses (with the standard message) when the player may not edit. */
    private boolean requireEdit() {
        if (canEdit()) return true;
        chat(EnumChatFormatting.RED + SumoPermissions.DENIED);
        return false;
    }

    /**
     * Back to the lobby from any state. The Sumo menu opens only when
     * {@code openMenu} is set: on first entering the world and on /stopsumo.
     */
    void enterLobby(boolean openMenu) {
        removeBot();
        phase = Phase.LOBBY;
        timer = 0;
        fightTicks = 0;
        if (player == null) return;
        player.setGameType(WorldSettings.GameType.ADVENTURE);
        teleport(layout.lobbySpawn);
        clearTitle();
        publish();
        if (openMenu) SumoView.request(SumoView.Screen.LOBBY);
    }

    /** START SUMO / REMATCH. */
    boolean startMatch() {
        if (player == null) return false;
        if (phase == Phase.EDIT) {
            chat(P + "Leave edit mode first (/sumo edit off).");
            return false;
        }
        if (phase == Phase.COUNTDOWN || phase == Phase.FIGHT) {
            chat(P + "A match is already running. /stopsumo ends it.");
            return false;
        }
        GridArena shape = ArenaScanner.scan(world, layout);
        if (shape == null) {
            chat(EnumChatFormatting.RED + "[Sumo] The arena isn't ready: both start positions need ground under them, on the same platform. "
                    + EnumChatFormatting.GRAY + "Use /sumo edit and /sumo setspawn player|ai.");
            return false;
        }
        removeBot();
        brain.setArena(shape);
        brain.setDifficulty(new SumoDifficulty(skill()));
        brain.setPlaystyle(prefs.style());
        brain.resetRound();
        player.setGameType(WorldSettings.GameType.ADVENTURE);
        spawnBot();
        placeAtStarts();
        hitsByPlayer = 0;
        hitsByBot = 0;
        fightTicks = 0;
        phase = Phase.COUNTDOWN;
        timer = COUNTDOWN_TICKS;
        player.playerNetServerHandler.sendPacket(new S45PacketTitle(0, 22, 4));
        title(EnumChatFormatting.LIGHT_PURPLE + "3", scoreLine());
        publish();
        return true;
    }

    /**
     * /stopsumo: end the match now (not counted, also between rounds), remove the
     * opponent and return to the lobby. {@code openMenu}: open the Sumo menu once.
     */
    boolean stopMatch(boolean openMenu) {
        if (phase != Phase.COUNTDOWN && phase != Phase.FIGHT && phase != Phase.FINISHED) return false;
        brain.resetRound();
        saveStats(); // keeps what it learned this match; the match itself isn't counted
        chat(P + (phase == Phase.FINISHED ? "Stopped. Back to the lobby." : "Match stopped (not counted). Back to the lobby."));
        enterLobby(openMenu);
        return true;
    }

    void setEdit(boolean on) {
        if (player == null) return;
        if (on && !requireEdit()) return;
        if (on) {
            if (phase == Phase.EDIT) {
                chat(P + "Edit mode is already on.");
                return;
            }
            if (phase == Phase.COUNTDOWN || phase == Phase.FIGHT) brain.resetRound();
            removeBot();
            phase = Phase.EDIT;
            timer = 0;
            clearTitle();
            player.setGameType(WorldSettings.GameType.CREATIVE);
            chat(P + EnumChatFormatting.YELLOW + "Edit mode on" + EnumChatFormatting.GRAY
                    + ": build and break freely. /sumo setspawn player|ai|lobby moves the starts, "
                    + "/sumo save keeps your arena, /sumo edit off to finish.");
            publish();
        } else {
            if (phase != Phase.EDIT) {
                chat(P + "Edit mode isn't on.");
                return;
            }
            saveLayout();
            chat(P + "Edit mode off. Your blocks stay as they are; /sumo save stores them as your arena save.");
            enterLobby(false);
        }
    }

    // ---- Every server tick (after the world ticked).

    void tick() {
        if (phase == Phase.STOPPED || phase == Phase.WAITING || player == null) return;
        if (player.isDead || player.worldObj != world) {
            stop("player gone");
            return;
        }
        SumoWorldSetup.keepClear(world);
        // Creative only in edit mode, and edit mode only for the map's owner: a game
        // mode changed any other way (cheats, LAN) is put back at once.
        if (phase == Phase.EDIT && !canEdit()) {
            chat(EnumChatFormatting.RED + SumoPermissions.DENIED);
            enterLobby(false);
        } else if (phase != Phase.EDIT && player.theItemInWorldManager.getGameType() != WorldSettings.GameType.ADVENTURE) {
            player.setGameType(WorldSettings.GameType.ADVENTURE);
        }
        switch (phase) {
            case LOBBY:
                if (player.posY < layout.lobbySpawn.y - 12) teleport(layout.lobbySpawn);
                break;
            case EDIT:
                if (player.posY < 1) teleport(layout.lobbySpawn);
                break;
            case COUNTDOWN:
                if (bot == null || bot.isDead) spawnBot();
                hold(layout.playerSpawn);
                timer--;
                if (timer > 0 && timer % 20 == 0) title(EnumChatFormatting.LIGHT_PURPLE.toString() + (timer / 20), null);
                if (timer <= 0) {
                    title(EnumChatFormatting.DARK_PURPLE + "FIGHT!", null);
                    phase = Phase.FIGHT;
                    fightTicks = 0;
                    publish();
                }
                break;
            case FIGHT:
                if (bot == null || bot.isDead) spawnBot();
                fightTicks++;
                double lose = layout.loseY();
                boolean playerOut = player.posY < lose;
                boolean botOut = bot.posY < lose;
                if (playerOut && botOut) endMatch(SumoStats.Result.DRAW);
                else if (botOut) endMatch(SumoStats.Result.WIN);
                else if (playerOut) endMatch(SumoStats.Result.LOSS);
                else if (fightTicks >= MAX_FIGHT_TICKS) endMatch(SumoStats.Result.DRAW);
                break;
            case FINISHED:
                // The result shows as a title; the next round starts by itself (no menu).
                if (player.posY < layout.loseY() - 10) teleport(layout.playerSpawn);
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

    /** The opponent's AI step, from its own entity tick: only a FIGHT lets it act. */
    void driveBot(EntitySumoBot e) {
        if (e != bot || player == null) return;
        if (phase != Phase.FIGHT) {
            ArenaLayout.Spot s = layout.aiSpawn;
            e.setPosition(s.x, s.y, s.z);
            e.motionX = 0;
            e.motionY = 0;
            e.motionZ = 0;
            e.fallDistance = 0;
            e.idle((float) SumoBrain.yawTo(e.posX, e.posZ, player.posX, player.posZ));
            return;
        }
        selfBody.set(e.posX, e.posY, e.posZ, e.rotationYaw, e.rotationPitch, e.onGround, e.hurtTime);
        targetBody.set(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch,
                player.onGround, player.hurtTime);
        e.apply(brain.tick(selfBody, targetBody), player, brain);
    }

    /** Damage never lowers health in the Sumo world; knockback and the hurt flash stay. */
    void onHurt(EntityLivingBase victim, Entity attacker) {
        if (phase != Phase.FIGHT) return;
        if (victim == bot && attacker == player) {
            hitsByPlayer++;
            brain.onHitTaken(player.getDistanceToEntity(bot));
        }
    }

    /** Whether hits between the two count at all right now (no hitting during the countdown). */
    boolean combatAllowed() {
        return phase == Phase.FIGHT;
    }

    void onBotHit() {
        hitsByBot++;
    }

    private void endMatch(SumoStats.Result result) {
        long ms = fightTicks * 50L;
        if (result == SumoStats.Result.WIN) scorePlayer++;
        else if (result == SumoStats.Result.LOSS) scoreBot++;
        stats.record(result, ms, hitsByPlayer, hitsByBot, System.currentTimeMillis(), prefs.mode().adaptive());
        saveStats();
        String head;
        switch (result) {
            case WIN:
                head = EnumChatFormatting.GREEN + "You win!";
                break;
            case LOSS:
                head = EnumChatFormatting.RED + "You lose!";
                break;
            default:
                head = EnumChatFormatting.YELLOW + "Draw";
                break;
        }
        lastResult = head;
        lastDurationMs = ms;
        title(head, scoreLine());
        chat(head + EnumChatFormatting.GRAY + String.format(" (%.1fs, hits %d-%d) ", ms / 1000.0, hitsByPlayer, hitsByBot)
                + overallLine());
        chat(EnumChatFormatting.DARK_GRAY + "Next round in " + (NEXT_ROUND_TICKS / 20) + "s. /stopsumo or M to stop.");
        phase = Phase.FINISHED;
        timer = NEXT_ROUND_TICKS;
        placeAtStarts();
        publish();
    }

    // ---- Arena editing.

    boolean matchActive() {
        return phase == Phase.COUNTDOWN || phase == Phase.FIGHT;
    }

    void setSpawn(String which) {
        if (player == null || !requireEdit()) return;
        if (matchActive()) {
            chat(P + "Stop the match first (/stopsumo).");
            return;
        }
        ArenaLayout.Spot s = new ArenaLayout.Spot(player.posX, player.posY, player.posZ, player.rotationYaw);
        if (!s.valid()) {
            chat(EnumChatFormatting.RED + "[Sumo] That position can't be used.");
            return;
        }
        if (!layout.regionContains(s.x, s.y, s.z)) {
            chat(EnumChatFormatting.YELLOW + "[Sumo] Note: this spot is outside the saved area (x/z "
                    + layout.regionMinX + ".." + layout.regionMaxX + ", y " + layout.regionMinY + ".." + layout.regionMaxY
                    + "); blocks there aren't part of /sumo save.");
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
        chat(P + label + " set to " + s + " (facing where you look). Fall-off height is now " + String.format("%.1f", layout.loseY()) + ".");
        publish();
    }

    void saveArena() {
        if (!requireEdit()) return;
        if (matchActive()) {
            chat(P + "Stop the match first (/stopsumo).");
            return;
        }
        try {
            layout.savedEpochMs = System.currentTimeMillis();
            saveLayout();
            int blocks = ArenaStore.save(world, layout, ArenaLayout.snapshot(dataDir));
            chat(P + EnumChatFormatting.GREEN + "Arena saved" + EnumChatFormatting.GRAY + " (" + blocks
                    + " blocks and the start positions). The previous save was kept as a backup.");
        } catch (IOException e) {
            log.error("[Kestrel] cannot save the Sumo arena", e);
            chat(EnumChatFormatting.RED + "[Sumo] Could not save the arena: " + e.getMessage());
        }
        publish();
    }

    /**
     * The first time a copy of the official map is opened (before anyone could
     * change it), keep a block snapshot of it, so the owner's "Default arena"
     * brings back exactly the official map.
     */
    private void keepOfficialSnapshot() {
        File worldDir = world.getSaveHandler().getWorldDirectory();
        int v = OfficialMap.installedVersion(worldDir);
        if (v <= 0) return;
        File snap = officialSnapshot(v);
        if (snap.isFile()) return;
        try {
            ArenaStore.save(world, OfficialMap.layout(), snap);
        } catch (IOException e) {
            log.error("[Kestrel] cannot keep the official Sumo map snapshot", e);
        }
    }

    private File officialSnapshot(int version) {
        return new File(ArenaLayout.dir(dataDir), "arena-blocks.official-v" + version + ".dat");
    }

    /** "Default arena": the official map's blocks and start positions (what's there is backed up first). */
    void restoreOfficial() {
        if (!requireEdit()) return;
        if (matchActive()) stopMatch(false);
        File snap = officialSnapshot(OfficialMap.installedVersion(world.getSaveHandler().getWorldDirectory()));
        if (!snap.isFile()) {
            chat(EnumChatFormatting.RED + "[Sumo] The official map snapshot isn't available in this world.");
            return;
        }
        try {
            File backup = ArenaStore.backupCurrent(world, layout, ArenaLayout.dir(dataDir), "before-official");
            ArenaStore.restore(world, snap);
            layout = OfficialMap.layout();
            saveLayout();
            chat(P + EnumChatFormatting.GREEN + "The official Sumo map is back" + EnumChatFormatting.GRAY
                    + " (what was there is backed up in " + backup.getName() + ").");
            if (phase != Phase.EDIT) enterLobby(false);
        } catch (IOException e) {
            chat(EnumChatFormatting.RED + "[Sumo] Not changed: " + e.getMessage());
        }
        publish();
    }

    /** Restore the last {@code /sumo save}. */
    void restoreArena() {
        if (!requireEdit()) return;
        if (matchActive()) stopMatch(false);
        try {
            ArenaLayout saved = ArenaStore.restore(world, ArenaLayout.snapshot(dataDir));
            if (saved == null) {
                chat(P + "There is no saved arena yet. /sumo save makes one.");
                return;
            }
            layout = saved;
            saveLayout();
            chat(P + EnumChatFormatting.GREEN + "Your last saved arena is back.");
            if (phase != Phase.EDIT) enterLobby(false);
        } catch (IOException e) {
            chat(EnumChatFormatting.RED + "[Sumo] Could not restore the arena: " + e.getMessage());
        }
        publish();
    }

    /**
     * Rebuild a default square platform ({@code size}, {@code material}) and lobby.
     * What was there is backed up first, never just deleted.
     */
    void rebuildDefault(int size, String material, String reason) {
        if (!requireEdit()) return;
        if (matchActive()) stopMatch(false);
        try {
            File backup = ArenaStore.backupCurrent(world, layout, ArenaLayout.dir(dataDir), reason);
            ArenaLayout next = ArenaLayout.defaults();
            next.platformSize = ArenaLayout.clampSize(size);
            next.platformMaterial = ArenaLayout.isMaterial(material) ? material : next.platformMaterial;
            next.applyDefaultSpawns();
            next.regionMinX = layout.regionMinX;
            next.regionMaxX = layout.regionMaxX;
            next.regionMinY = layout.regionMinY;
            next.regionMaxY = layout.regionMaxY;
            next.regionMinZ = layout.regionMinZ;
            next.regionMaxZ = layout.regionMaxZ;
            layout = next;
            SumoWorldSetup.buildDefault(world, layout);
            saveLayout();
            chat(P + "Built a " + layout.platformSize + " x " + layout.platformSize + " " + ArenaLayout.materialLabel(layout.platformMaterial)
                    + " platform. What was there before is backed up in " + backup.getName()
                    + ". /sumo reset confirm brings back your last save; /sumo save keeps this one.");
            if (phase != Phase.EDIT) enterLobby(false);
        } catch (IOException e) {
            chat(EnumChatFormatting.RED + "[Sumo] Not changed: the backup of the current arena failed (" + e.getMessage() + ").");
        }
        publish();
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
        chat(P + "Playstyle: " + s.label + " - " + s.description);
        publish();
    }

    void resetStats() throws IOException {
        stats = SumoStats.reset(statsFile());
        brain = new SumoBrain(new SumoDifficulty(skill()), stats.profile, rng);
        brain.setPlaystyle(prefs.style());
        scorePlayer = 0;
        scoreBot = 0;
        if (matchActive() || phase == Phase.FINISHED) enterLobby(false);
        publish();
    }

    private double skill() {
        return prefs.mode().skill(stats.difficulty);
    }

    // ---- Helpers.

    private void placeAtStarts() {
        teleport(layout.playerSpawn);
        if (bot != null) {
            ArenaLayout.Spot b = layout.aiSpawn;
            bot.setLocationAndAngles(b.x, b.y, b.z, b.yaw, 0F);
            bot.rotationYawHead = b.yaw;
            bot.motionX = 0;
            bot.motionY = 0;
            bot.motionZ = 0;
            bot.fallDistance = 0;
            bot.setHealth(bot.getMaxHealth());
        }
    }

    private void teleport(ArenaLayout.Spot s) {
        if (player == null) return;
        player.fallDistance = 0;
        player.extinguish();
        player.setHealth(player.getMaxHealth());
        player.playerNetServerHandler.setPlayerLocation(s.x, s.y, s.z, s.yaw, 0F);
    }

    /** Keep the player at a start (looking around is fine). */
    private void hold(ArenaLayout.Spot s) {
        double dx = player.posX - s.x;
        double dz = player.posZ - s.z;
        if (dx * dx + dz * dz > 0.01 || Math.abs(player.posY - s.y) > 0.6) {
            player.fallDistance = 0;
            player.playerNetServerHandler.setPlayerLocation(s.x, s.y, s.z, player.rotationYaw, player.rotationPitch);
        }
    }

    private void spawnBot() {
        if (bot != null && !bot.isDead) return;
        bot = new EntitySumoBot(world, this);
        ArenaLayout.Spot b = layout.aiSpawn;
        bot.setLocationAndAngles(b.x, b.y, b.z, b.yaw, 0F);
        bot.rotationYawHead = b.yaw;
        world.spawnEntityInWorld(bot);
    }

    /** The opponent disappears: no AI, no entity, nothing left to tick. */
    private void removeBot() {
        if (bot != null) {
            bot.setDead();
            bot = null;
        }
        // Any other copy (none should exist) goes too.
        for (Object o : world.loadedEntityList) {
            if (o instanceof EntitySumoBot) ((Entity) o).setDead();
        }
    }

    /** Leftovers that don't belong in the practice world (items, animals, old opponents). */
    private void removeStrays() {
        List<Entity> remove = new ArrayList<Entity>();
        for (Object o : world.loadedEntityList) {
            Entity e = (Entity) o;
            if (e instanceof EntityPlayer || e == bot) continue;
            if (e instanceof EntitySumoBot || e instanceof EntityItem || e instanceof EntityXPOrb
                    || (e instanceof EntityLivingBase && !(e instanceof net.minecraft.entity.item.EntityArmorStand))) {
                remove.add(e);
            }
        }
        for (Entity e : remove) e.setDead();
    }

    private void publish() {
        if (phase == Phase.STOPPED || stats == null) return;
        SumoView.publish(new SumoView(this));
    }

    private void saveStats() {
        if (stats == null) return;
        try {
            stats.save(statsFile());
        } catch (IOException e) {
            log.error("[Kestrel] cannot save Sumo statistics to {}", statsFile(), e);
        }
    }

    private void saveLayout() {
        if (layout == null) return;
        try {
            layout.save(ArenaLayout.file(dataDir));
        } catch (IOException e) {
            log.error("[Kestrel] cannot save the Sumo layout", e);
        }
    }

    private void savePrefs() {
        try {
            prefs.save(SumoPrefs.file(dataDir));
        } catch (IOException e) {
            log.error("[Kestrel] cannot save the Sumo opponent settings", e);
        }
    }

    // ---- Messages.

    String scoreLine() {
        return EnumChatFormatting.WHITE + "You " + scorePlayer + EnumChatFormatting.GRAY + " - "
                + EnumChatFormatting.WHITE + scoreBot + " " + EntitySumoBot.NAME;
    }

    String overallLine() {
        return EnumChatFormatting.DARK_GRAY + String.format("Overall %d-%d (%d%%), streak %d, best %d, difficulty %d%%",
                stats.wins, stats.losses, stats.winRatePercent(), stats.winStreak(), stats.bestWinStreak,
                (int) Math.round(skill() * 100));
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
