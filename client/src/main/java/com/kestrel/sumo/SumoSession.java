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
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One Sumo practice session: from loading the Sumo world until leaving it.
 * Runs on the integrated server thread only. Rounds repeat (countdown, fight,
 * result) with a session score; every finished round is one match in the
 * overall statistics, saved right away and again when the session stops.
 */
final class SumoSession {
    enum Phase { WAITING, COUNTDOWN, FIGHT, ROUND_END, STOPPED }

    private static final int COUNTDOWN_TICKS = 60;
    private static final int ROUND_END_TICKS = 60;
    /** A round that nobody wins in 3 minutes is a draw. */
    private static final int MAX_FIGHT_TICKS = 20 * 180;

    private final WorldServer world;
    private final File statsFile;
    private final Logger log;
    private final Random rng = new Random();
    private SumoStats stats;
    private SumoBrain brain;
    private EntitySumoBot bot;
    private EntityPlayerMP player;
    private Phase phase = Phase.WAITING;
    private int timer;
    private int fightTicks;
    private int scorePlayer;
    private int scoreBot;
    private int roundHitsByPlayer;
    private int roundHitsByBot;
    private final SumoBrain.Body selfBody = new SumoBrain.Body();
    private final SumoBrain.Body targetBody = new SumoBrain.Body();

    SumoSession(WorldServer world, File statsFile, Logger log) {
        this.world = world;
        this.statsFile = statsFile;
        this.log = log;
    }

    Phase phase() {
        return phase;
    }

    SumoStats stats() {
        return stats;
    }

    WorldServer world() {
        return world;
    }

    void start() {
        stats = SumoStats.load(statsFile);
        brain = new SumoBrain(new SumoDifficulty(stats.difficulty), stats.profile, rng);
        SumoWorldSetup.apply(world);
        log.info("[Kestrel] Sumo session started (matches {}, difficulty {})", stats.matches, String.format("%.2f", stats.difficulty));
    }

    boolean owns(EntitySumoBot e) {
        return phase != Phase.STOPPED && e == bot;
    }

    boolean isCombatant(Entity e) {
        return e != null && (e == bot || e == player);
    }

    void onPlayerJoin(EntityPlayerMP p) {
        if (phase == Phase.STOPPED || p.worldObj != world) return;
        if (player != null && player != p) return; // only the first player fights
        player = p;
        p.setGameType(WorldSettings.GameType.ADVENTURE);
        removeStrays();
        spawnBot();
        chat(EnumChatFormatting.LIGHT_PURPLE + "Sumo practice" + EnumChatFormatting.GRAY
                + " - knock " + EntitySumoBot.NAME + " off the platform. " + overallLine());
        beginRound();
    }

    void onPlayerLeave(EntityPlayer p) {
        if (p == player) stop("player left");
    }

    // ---- Every server tick (after the world ticked).

    void tick() {
        if (phase == Phase.STOPPED || phase == Phase.WAITING || player == null) return;
        if (player.isDead || player.worldObj != world) {
            stop("player gone");
            return;
        }
        SumoWorldSetup.keepClear(world);
        if (bot == null || bot.isDead) spawnBot();
        switch (phase) {
            case COUNTDOWN:
                holdPlayer();
                timer--;
                if (timer > 0 && timer % 20 == 0) title(EnumChatFormatting.LIGHT_PURPLE.toString() + (timer / 20), null);
                if (timer <= 0) {
                    title(EnumChatFormatting.DARK_PURPLE + "FIGHT!", null);
                    phase = Phase.FIGHT;
                    fightTicks = 0;
                }
                break;
            case FIGHT:
                fightTicks++;
                boolean playerOut = SumoArena.fell(player.posY);
                boolean botOut = SumoArena.fell(bot.posY);
                if (playerOut && botOut) endRound(SumoStats.Result.DRAW);
                else if (botOut) endRound(SumoStats.Result.WIN);
                else if (playerOut) endRound(SumoStats.Result.LOSS);
                else if (fightTicks >= MAX_FIGHT_TICKS) endRound(SumoStats.Result.DRAW);
                break;
            case ROUND_END:
                holdPlayer();
                if (--timer <= 0) beginRound();
                break;
            default:
                break;
        }
    }

    /** The bot's AI step, from its own entity tick. */
    void driveBot(EntitySumoBot e) {
        if (e != bot || player == null) return;
        if (phase != Phase.FIGHT) {
            double[] s = SumoArena.botSpawn();
            e.setPosition(s[0], s[1], s[2]);
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

    /** Damage between the two combatants never lowers health; knockback and the hurt flash stay. */
    void onHurt(EntityLivingBase victim, Entity attacker) {
        if (phase != Phase.FIGHT) return;
        if (victim == bot && attacker == player) {
            roundHitsByPlayer++;
            brain.onHitTaken(player.getDistanceToEntity(bot));
        }
    }

    void onBotHit() {
        roundHitsByBot++;
    }

    void stop(String reason) {
        if (phase == Phase.STOPPED) return;
        Phase was = phase;
        phase = Phase.STOPPED;
        if (bot != null) {
            bot.setDead();
            bot = null;
        }
        if (brain != null) brain.resetRound(); // closes the current strafe observation
        save();
        log.info("[Kestrel] Sumo session stopped ({}; was {}), session score {}-{}", reason, was, scorePlayer, scoreBot);
        player = null;
    }

    /** Reset overall statistics, difficulty and learning (keeps a backup of the old file). */
    void resetStats() throws IOException {
        stats = SumoStats.reset(statsFile);
        brain = new SumoBrain(new SumoDifficulty(stats.difficulty), stats.profile, rng);
        scorePlayer = 0;
        scoreBot = 0;
        if (phase != Phase.STOPPED && player != null) beginRound();
    }

    // ---- Rounds.

    private void beginRound() {
        phase = Phase.COUNTDOWN;
        timer = COUNTDOWN_TICKS;
        roundHitsByPlayer = 0;
        roundHitsByBot = 0;
        brain.setDifficulty(new SumoDifficulty(stats.difficulty));
        brain.resetRound();
        placeAtSpawns();
        sendTimes();
        title(EnumChatFormatting.LIGHT_PURPLE + "3", scoreLine());
    }

    private void endRound(SumoStats.Result result) {
        long ms = fightTicks * 50L;
        if (result == SumoStats.Result.WIN) scorePlayer++;
        else if (result == SumoStats.Result.LOSS) scoreBot++;
        stats.record(result, ms, roundHitsByPlayer, roundHitsByBot, System.currentTimeMillis());
        save();
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
        title(head, scoreLine());
        chat(head + EnumChatFormatting.GRAY + String.format(" (%.1fs, hits %d-%d) ", ms / 1000.0, roundHitsByPlayer, roundHitsByBot)
                + overallLine());
        phase = Phase.ROUND_END;
        timer = ROUND_END_TICKS;
        placeAtSpawns();
    }

    private void placeAtSpawns() {
        double[] p = SumoArena.playerSpawn();
        player.fallDistance = 0;
        player.extinguish();
        player.setHealth(player.getMaxHealth());
        player.playerNetServerHandler.setPlayerLocation(p[0], p[1], p[2], (float) p[3], 0F);
        if (bot != null) {
            double[] b = SumoArena.botSpawn();
            bot.setLocationAndAngles(b[0], b[1], b[2], (float) b[3], 0F);
            bot.rotationYawHead = (float) b[3];
            bot.motionX = 0;
            bot.motionY = 0;
            bot.motionZ = 0;
            bot.fallDistance = 0;
            bot.setHealth(bot.getMaxHealth());
        }
    }

    /** Keep the player at the start during the countdown and between rounds (looking around is fine). */
    private void holdPlayer() {
        double[] p = SumoArena.playerSpawn();
        double dx = player.posX - p[0];
        double dz = player.posZ - p[2];
        if (dx * dx + dz * dz > 0.01 || Math.abs(player.posY - p[1]) > 0.6) {
            player.fallDistance = 0;
            player.playerNetServerHandler.setPlayerLocation(p[0], p[1], p[2], player.rotationYaw, player.rotationPitch);
        }
    }

    private void spawnBot() {
        if (bot != null && !bot.isDead) return;
        bot = new EntitySumoBot(world, this);
        double[] b = SumoArena.botSpawn();
        bot.setLocationAndAngles(b[0], b[1], b[2], (float) b[3], 0F);
        bot.rotationYawHead = (float) b[3];
        world.spawnEntityInWorld(bot);
    }

    /** Anything else in the world (items, stray opponents from a crash, animals) goes. */
    private void removeStrays() {
        List<Entity> remove = new ArrayList<Entity>();
        for (Object o : world.loadedEntityList) {
            Entity e = (Entity) o;
            if (e instanceof EntityPlayer) continue;
            if (e == bot) continue;
            if (e instanceof EntitySumoBot || e instanceof EntityItem || e instanceof EntityXPOrb || e instanceof EntityLivingBase) {
                remove.add(e);
            }
        }
        for (Entity e : remove) e.setDead();
    }

    private void save() {
        if (stats == null) return;
        try {
            stats.save(statsFile);
        } catch (IOException e) {
            log.error("[Kestrel] cannot save Sumo statistics to {}", statsFile, e);
        }
    }

    // ---- Messages.

    private String scoreLine() {
        return EnumChatFormatting.WHITE + "You " + scorePlayer + EnumChatFormatting.GRAY + " - "
                + EnumChatFormatting.WHITE + scoreBot + " " + EntitySumoBot.NAME;
    }

    private String overallLine() {
        return EnumChatFormatting.DARK_GRAY + String.format("Overall %d-%d (%d%%), streak %d, best %d, difficulty %d%%",
                stats.wins, stats.losses, stats.winRatePercent(), stats.winStreak(), stats.bestWinStreak,
                (int) Math.round(stats.difficulty * 100));
    }

    private void sendTimes() {
        player.playerNetServerHandler.sendPacket(new S45PacketTitle(0, 22, 4));
    }

    private void title(String title, String subtitle) {
        if (player == null) return;
        if (subtitle != null) {
            player.playerNetServerHandler.sendPacket(new S45PacketTitle(S45PacketTitle.Type.SUBTITLE, new ChatComponentText(subtitle)));
        }
        player.playerNetServerHandler.sendPacket(new S45PacketTitle(S45PacketTitle.Type.TITLE, new ChatComponentText(title)));
    }

    void chat(String text) {
        if (player == null) return;
        IChatComponent c = new ChatComponentText(text);
        player.playerNetServerHandler.sendPacket(new S02PacketChat(c, (byte) 0));
    }
}
