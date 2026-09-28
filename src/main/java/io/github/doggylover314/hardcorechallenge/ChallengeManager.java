package io.github.doggylover314.hardcorechallenge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.config.Settings;
import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.BossKill;
import io.github.doggylover314.hardcorechallenge.core.DeathRecord;
import io.github.doggylover314.hardcorechallenge.core.Outcome;
import io.github.doggylover314.hardcorechallenge.core.Roster;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.RunRecord;
import io.github.doggylover314.hardcorechallenge.core.RunStateMachine;
import io.github.doggylover314.hardcorechallenge.core.TimeFormat;
import io.github.doggylover314.hardcorechallenge.data.DataStore;
import io.github.doggylover314.hardcorechallenge.player.PlayerResetter;
import io.github.doggylover314.hardcorechallenge.ui.Announcer;
import io.github.doggylover314.hardcorechallenge.ui.Hud;
import io.github.doggylover314.hardcorechallenge.ui.Webhook;
import io.github.doggylover314.hardcorechallenge.world.RunWorlds;
import io.github.doggylover314.hardcorechallenge.world.WorldService;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the challenge: turns deaths, boss kills and commands into state machine transitions and
 * carries out the side effects (worlds, players, announcements, persistence).
 *
 * <p>Everything here runs on the main server thread.</p>
 */
public final class ChallengeManager {
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final HardcoreChallengePlugin plugin;
    private final Logger logger;
    private final DataStore store;
    private final WorldService worlds;
    private final RunStateMachine machine;
    private final Webhook webhook;
    private final SecureRandom random = new SecureRandom();

    private Settings settings;
    private Messages messages;
    private Announcer announcer;
    private Hud hud;
    private Roster roster = new Roster();

    /** Worlds of the current (or most recently finished) run, if loaded. */
    private RunWorlds current;
    /** Folder paths of those worlds, persisted so they can be cleaned up even if never loaded again. */
    private List<String> currentPaths = List.of();

    private Transition transition;
    private BukkitTask victoryTask;
    private BukkitTask fireworksTask;
    private BukkitTask ticker;
    private boolean resolveScheduled;
    /** Vanilla death message of the pending death, shown when it resolves. */
    private Component pendingDeathMessage;

    public ChallengeManager(HardcoreChallengePlugin plugin, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.messages = messages;
        this.store = new DataStore(plugin.getDataPath(), logger);
        this.worlds = new WorldService(plugin, store, () -> currentPaths);
        this.machine = new RunStateMachine(() -> System.nanoTime() / 1_000_000L, System::currentTimeMillis);
        this.webhook = new Webhook(logger);
    }

    // =================================================================== lifecycle

    public void enable() {
        DataStore.PersistedState state = store.loadState();
        store.loadHistory();
        store.loadPendingDeletions();

        machine.restore(state.run());
        roster = state.roster();
        currentPaths = List.copyOf(state.worldPaths());
        configureMachine();

        announcer = new Announcer(settings, messages, logger);
        hud = new Hud(machine, this::aliveParticipantCount, roster::size, settings, messages);

        resume();
        worlds.processPendingDeletions();

        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
    }

    private void resume() {
        RunPhase phase = machine.phase();
        int run = machine.runNumber();
        switch (phase) {
            case RUNNING, VICTORY -> {
                Optional<RunWorlds> loaded = worlds.load(run, machine.seed(), currentPaths, settings.hardcoreFlag(), settings.difficulty());
                if (loaded.isEmpty()) {
                    logger.severe("Run #" + run + " could not be resumed because its worlds failed to load. The run has been stopped.");
                    machine.stop();
                    save();
                    return;
                }
                current = loaded.get();
                currentPaths = current.paths();
                logger.info("Resumed run #" + run + " at " + TimeFormat.clock(machine.elapsedMillis())
                        + " with " + machine.bossKills().size() + " boss(es) down");
                hud.showAll();
                if (phase == RunPhase.VICTORY) {
                    // The celebration was interrupted by the restart; finish it shortly.
                    victoryTask = Bukkit.getScheduler().runTaskLater(plugin, this::finishVictory, 100L);
                }
            }
            case RESETTING -> {
                logger.info("The server stopped during a reset; starting run #" + (run + 1) + " now.");
                Bukkit.getScheduler().runTask(plugin, () -> runTransition("resuming after a restart"));
            }
            case IDLE -> {
                // Keep the last world around for sightseeing if it still exists.
                if (run > 0 && !currentPaths.isEmpty() && currentPaths.stream().allMatch(p -> Files.isDirectory(Paths.get(p)))) {
                    worlds.load(run, machine.seed(), currentPaths, settings.hardcoreFlag(), settings.difficulty())
                            .ifPresent(loaded -> current = loaded);
                }
            }
        }
        save();
    }

    public void disable() {
        if (machine.hasPendingDeath()) {
            // A death was waiting for the next tick; it stands.
            machine.resolvePendingDeath(Long.MAX_VALUE).ifPresent(death ->
                    store.appendHistory(machine.toRecord(Outcome.DEATH, death, null)));
        }
        cancel(ticker);
        cancel(victoryTask);
        cancel(fireworksTask);
        if (transition != null) {
            transition.cancel();
        }
        if (hud != null) {
            hud.hide();
        }
        worlds.shutdown();
        store.shutdown();
        store.saveStateNow(persistedState());
    }

    public void reload() {
        plugin.reloadConfig();
        settings = Settings.load(plugin.getConfig(), logger);
        messages = new Messages(plugin.getConfig(), logger);
        configureMachine();
        announcer.reload(settings, messages);
        hud.reload(settings, messages);
        if (machine.reevaluateVictory()) {
            startVictory();
        }
    }

    private void configureMachine() {
        machine.configure(settings.bosses(), settings.victoryRequireAllBosses(), settings.victoryEnabled());
    }

    private void tickSecond() {
        hud.update();
        // Checkpoint the clock periodically so a crash loses at most a minute of run time.
        if (machine.phase() == RunPhase.RUNNING && Bukkit.getCurrentTick() % 1200 < 20) {
            save();
        }
    }

    private DataStore.PersistedState persistedState() {
        return new DataStore.PersistedState(machine.snapshot(), currentPaths, roster);
    }

    private void save() {
        store.saveState(persistedState());
    }

    // ================================================================= queries

    public RunStateMachine machine() {
        return machine;
    }

    public Roster roster() {
        return roster;
    }

    public Messages messages() {
        return messages;
    }

    public Settings settings() {
        return settings;
    }

    public Optional<RunWorlds> currentWorlds() {
        return Optional.ofNullable(current);
    }

    /** Whether the world belongs to the current (or kept) run. */
    public boolean isRunWorld(World world) {
        return current != null && current.contains(world);
    }

    private int aliveParticipantCount() {
        int alive = 0;
        for (UUID id : roster.participants().keySet()) {
            if (roster.isActive(id)) {
                alive++;
            }
        }
        return alive;
    }

    // ============================================================== transitions

    /** /hcc start */
    public void start(CommandSender sender) {
        switch (machine.phase()) {
            case RUNNING, VICTORY -> sender.sendMessage(messages.chat("run-already-active"));
            case RESETTING -> sender.sendMessage(messages.chat("transition-in-progress"));
            case IDLE -> {
                if (!ensureParticipants(sender)) {
                    return;
                }
                machine.beginTransition();
                save();
                runTransition(messages.raw("default-reset-reason"));
            }
        }
    }

    /** /hcc reset [reason] */
    public void forceReset(CommandSender sender, String reason) {
        RunPhase phase = machine.phase();
        if (phase == RunPhase.RESETTING) {
            sender.sendMessage(messages.chat("transition-in-progress"));
            return;
        }
        if (phase == RunPhase.IDLE) {
            start(sender);
            return;
        }
        String why = reason == null || reason.isBlank() ? "forced by " + sender.getName() : reason;
        cancel(victoryTask);
        cancel(fireworksTask);
        if (phase == RunPhase.RUNNING) {
            RunRecord record = machine.toRecord(Outcome.FORCED_RESET, null, why);
            store.appendHistory(record);
            postRunEnd(record);
        }
        announcer.chat("reset-forced", Placeholder.unparsed("player", sender.getName()), Placeholder.unparsed("reason", why));
        machine.beginTransition();
        save();
        runTransition(why);
    }

    /** /hcc stop */
    public void stop(CommandSender sender) {
        RunPhase phase = machine.phase();
        if (phase == RunPhase.IDLE) {
            sender.sendMessage(messages.chat("run-not-active"));
            return;
        }
        if (phase == RunPhase.RUNNING) {
            RunRecord record = machine.toRecord(Outcome.STOPPED, null, "stopped by " + sender.getName());
            store.appendHistory(record);
            postRunEnd(record);
        }
        cancel(victoryTask);
        cancel(fireworksTask);
        cancelTransition();
        machine.stop();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        hud.hide();
        save();
        announcer.chatAlways("stopped", Placeholder.unparsed("player", sender.getName()));
    }

    /**
     * Runs the countdown and creates the next world at the same time. When both are done, everyone is
     * moved over and the old worlds are retired. The machine must already be in RESETTING.
     */
    private void runTransition(String reason) {
        cancelTransition();
        cancel(victoryTask);
        cancel(fireworksTask);

        int newRun = machine.runNumber() + 1;
        while (worlds.isRunNumberTaken(newRun)) {
            newRun++;
        }
        long seed = newSeed(machine.seed());
        transition = new Transition(machine.runNumber(), currentPaths, newRun, reason);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        hud.showAll();
        announcer.chat("resetting", Placeholder.unparsed("reason", reason));
        logger.info("Creating run #" + newRun + ": " + reason);

        Transition t = transition;
        // Wall-clock deadline: creating a world blocks the main thread for a few seconds, and that
        // time should overlap the countdown rather than extend it.
        t.deadlineMillis = System.currentTimeMillis() + settings.resetCountdownSeconds() * 1000L;
        t.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> countdownTick(t), 0L, 5L);
        worlds.create(newRun, seed, settings.hardcoreFlag(), settings.difficulty()).whenComplete((created, error) -> {
            if (t.cancelled) {
                if (created != null) {
                    // Nobody will use these; clean them up.
                    worlds.retire(t.newRun, created.paths(), fallbackLocation(), 0);
                }
                return;
            }
            if (error != null) {
                failTransition(t, error);
                return;
            }
            t.worlds = created;
            tryCompleteTransition(t);
        });
    }

    private void countdownTick(Transition t) {
        if (t.cancelled) {
            return;
        }
        long remainingMillis = t.deadlineMillis - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            cancel(t.countdownTask);
            t.countdownDone = true;
            tryCompleteTransition(t);
            return;
        }
        int seconds = (int) ((remainingMillis + 999) / 1000);
        if (seconds == t.lastShownSecond) {
            return;
        }
        t.lastShownSecond = seconds;
        TagResolver[] placeholders = {
                Placeholder.unparsed("seconds", String.valueOf(seconds)),
                Placeholder.unparsed("reason", t.reason)};
        if (announcer.titlesEnabled()) {
            announcer.countdownTitle(placeholders);
        } else if (seconds <= 5 || seconds % 5 == 0) {
            announcer.chat("countdown-chat", placeholders);
        }
        announcer.sound("countdown-tick", seconds <= 3 ? 1.5f : 1f);
    }

    private void tryCompleteTransition(Transition t) {
        if (t.cancelled || !t.countdownDone || t.worlds == null || t.completed) {
            return;
        }
        t.completed = true;
        transition = null;

        RunWorlds next = t.worlds;
        machine.beginRun(t.newRun, next.overworld().getName(), next.overworld().getSeed());
        logger.info("Run #" + t.newRun + " started in " + next.overworld().getName() + " (seed " + machine.seed() + ")");
        current = next;
        currentPaths = next.paths();
        roster.clearEliminations();
        save();

        Location spawn = next.spawn();
        List<CompletableFuture<?>> moves = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            moves.add(moveIntoRun(player, spawn));
        }

        hud.showAll();
        announceRunStart();

        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                onMain(() -> {
                    save();
                    if (t.oldRun > 0 && t.oldRun != machine.runNumber()) {
                        worlds.retire(t.oldRun, t.oldPaths, current != null ? current.spawn() : fallbackLocation(), settings.keepOldWorlds());
                    }
                }));
    }

    /** Teleports a player to the run spawn; participants are wiped and set to survival on arrival. */
    private CompletableFuture<?> moveIntoRun(Player player, Location spawn) {
        boolean participant = roster.isActive(player.getUniqueId());
        if (participant) {
            roster.markSynced(player.getUniqueId(), machine.runNumber());
            // Clear straight away so nothing from the old world travels, even if the teleport is slow.
            PlayerResetter.wipe(player);
        }
        int run = machine.runNumber();
        return player.teleportAsync(spawn).thenAccept(success -> onMain(() -> {
            if (!player.isOnline() || machine.runNumber() != run) {
                return;
            }
            if (!success) {
                player.teleport(spawn);
            }
            if (participant && roster.isActive(player.getUniqueId()) && machine.phase() == RunPhase.RUNNING) {
                PlayerResetter.prepareForRun(player);
            } else {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }));
    }

    /** Abandons a reset in progress, cleaning up any worlds it already created. */
    private void cancelTransition() {
        Transition t = transition;
        transition = null;
        if (t == null || t.completed) {
            return;
        }
        t.cancel();
        if (t.worlds != null) {
            worlds.retire(t.newRun, t.worlds.paths(), fallbackLocation(), 0);
        }
    }

    private void failTransition(Transition t, Throwable error) {
        logger.log(java.util.logging.Level.SEVERE, "Could not create run #" + t.newRun, error);
        t.cancel();
        transition = null;
        machine.stop();
        hud.hide();
        save();
        announcer.chatAlways("reset-failed", Placeholder.unparsed("error", String.valueOf(error.getMessage())));
    }

    private void announceRunStart() {
        TagResolver[] placeholders = {
                Placeholder.unparsed("run", String.valueOf(machine.runNumber())),
                Placeholder.unparsed("seed", String.valueOf(machine.seed())),
                Placeholder.unparsed("bosses", bossList(settings.bosses()))};
        announcer.chat("run-started", placeholders);
        announcer.title("run-started-title", "run-started-subtitle", Duration.ofSeconds(3), placeholders);
        announcer.sound("run-start");

        JsonObject payload = new JsonObject();
        payload.addProperty("event", "run_start");
        payload.addProperty("run", machine.runNumber());
        payload.addProperty("seed", machine.seed());
        payload.addProperty("world", machine.worldName());
        JsonArray names = new JsonArray();
        roster.participants().values().forEach(names::add);
        payload.add("participants", names);
        webhook.post(settings.webhookUrl(), "Run #" + machine.runNumber() + " started (seed " + machine.seed() + ")", payload);
    }

    private long newSeed(long previous) {
        long seed;
        do {
            seed = random.nextLong();
        } while (seed == previous || seed == 0L);
        return seed;
    }

    private boolean ensureParticipants(CommandSender sender) {
        if (!roster.isEmpty()) {
            return true;
        }
        List<String> added = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(HardcoreChallengePlugin.PERMISSION_PLAY)) {
                roster.add(player.getUniqueId(), player.getName());
                added.add(player.getName());
            }
        }
        if (added.isEmpty()) {
            sender.sendMessage(messages.chat("no-participants"));
            return false;
        }
        announcer.chat("participants-auto-added", Placeholder.unparsed("players", String.join(", ", added)));
        return true;
    }

    // ==================================================================== deaths

    /** Called for every player death (listener runs at HIGHEST, ignoring already-cancelled events). */
    public void handleDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (machine.phase() == RunPhase.IDLE || !isRunWorld(player.getWorld())) {
            return;
        }

        // Never let the hardcore death screen / spectator lock-in happen: cancel the death and
        // drive the outcome ourselves.
        Component vanillaMessage = event.deathMessage();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.setGameMode(GameMode.SPECTATOR);
                player.setFireTicks(0);
                player.setFallDistance(0f);
            }
        });

        if (machine.phase() != RunPhase.RUNNING) {
            return;
        }
        boolean counts = roster.isActive(player.getUniqueId())
                || (settings.resetOnDeathOf() == Settings.ResetOnDeathOf.ANYONE && player.getGameMode() != GameMode.SPECTATOR);
        if (!counts) {
            return;
        }

        DeathRecord death = describeDeath(player, event, vanillaMessage);
        if (!settings.autoResetOnDeath()) {
            roster.eliminate(player.getUniqueId());
            save();
            hud.update();
            announcer.chatAlways("eliminated",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.component("death_message", vanillaMessage != null ? vanillaMessage : Component.text(death.cause())),
                    Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis())));
            return;
        }
        reportRunEndingDeath(death, vanillaMessage);
    }

    /** Participant logged out while offline-death-grace is off. */
    public void handleQuit(Player player) {
        if (machine.phase() != RunPhase.RUNNING || settings.offlineDeathGrace() || !roster.isActive(player.getUniqueId())) {
            return;
        }
        Location loc = player.getLocation();
        DeathRecord forfeit = new DeathRecord(player.getUniqueId(), player.getName(), "disconnected", null,
                player.getName() + " left the game", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        roster.eliminate(player.getUniqueId());
        if (machine.reportDeath(forfeit, Bukkit.getCurrentTick()) == RunStateMachine.DeathResult.PENDING) {
            scheduleResolve();
        }
    }

    private void reportRunEndingDeath(DeathRecord death, Component vanillaMessage) {
        RunStateMachine.DeathResult result = machine.reportDeath(death, Bukkit.getCurrentTick());
        if (result == RunStateMachine.DeathResult.PENDING) {
            pendingDeathMessage = vanillaMessage;
            scheduleResolve();
        }
    }

    private void scheduleResolve() {
        if (resolveScheduled) {
            return;
        }
        resolveScheduled = true;
        // One tick later: if a victory lands in this same tick, it wins.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            resolveScheduled = false;
            machine.resolvePendingDeath(Bukkit.getCurrentTick()).ifPresent(this::endRunByDeath);
        }, 1L);
    }

    private void endRunByDeath(DeathRecord death) {
        RunRecord record = machine.toRecord(Outcome.DEATH, death, null);
        store.appendHistory(record);
        save();

        Component message = pendingDeathMessage != null ? pendingDeathMessage : Component.text(death.message() != null ? death.message() : death.cause());
        pendingDeathMessage = null;
        String time = TimeFormat.clock(record.durationMillis());
        TagResolver[] placeholders = {
                Placeholder.unparsed("player", death.playerName()),
                Placeholder.unparsed("cause", death.cause()),
                Placeholder.unparsed("killer", death.killer() == null ? "" : death.killer()),
                Placeholder.component("death_message", message),
                Placeholder.unparsed("x", String.valueOf(death.x())),
                Placeholder.unparsed("y", String.valueOf(death.y())),
                Placeholder.unparsed("z", String.valueOf(death.z())),
                Placeholder.unparsed("world", death.world() == null ? "?" : death.world()),
                Placeholder.unparsed("time", time),
                Placeholder.unparsed("run", String.valueOf(record.runNumber()))};
        if ("disconnected".equals(death.cause())) {
            announcer.chatAlways("forfeit", placeholders);
        } else {
            announcer.chatAlways("death", placeholders);
        }
        announcer.title("death-title", "death-subtitle", Duration.ofSeconds(3), placeholders);
        announcer.sound("death");
        postRunEnd(record);

        runTransition(death.playerName() + " died");
    }

    private DeathRecord describeDeath(Player player, PlayerDeathEvent event, Component vanillaMessage) {
        var source = event.getDamageSource();
        String cause = readable(source.getDamageType().getKey().getKey());
        Entity killerEntity = source.getCausingEntity() != null ? source.getCausingEntity() : source.getDirectEntity();
        String killer = null;
        if (killerEntity != null && !killerEntity.equals(player)) {
            if (killerEntity instanceof Player killerPlayer) {
                killer = killerPlayer.getName();
            } else if (killerEntity.customName() != null) {
                killer = PlainTextComponentSerializer.plainText().serialize(killerEntity.customName());
            } else {
                killer = readable(killerEntity.getType().getKey().getKey());
            }
        }
        String message = vanillaMessage == null ? null : PlainTextComponentSerializer.plainText().serialize(vanillaMessage);
        Location loc = player.getLocation();
        return new DeathRecord(player.getUniqueId(), player.getName(), cause, killer, message,
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private static String readable(String key) {
        return key.replace('_', ' ');
    }

    // ==================================================================== bosses

    public void handleBossDeath(Boss boss, World world) {
        if (!isRunWorld(world)) {
            return;
        }
        RunStateMachine.BossResult result = machine.reportBossKill(boss, Bukkit.getCurrentTick());
        if (result != RunStateMachine.BossResult.RECORDED && result != RunStateMachine.BossResult.VICTORY) {
            return;
        }
        save();
        int total = machine.trackedBosses().size();
        int count = (int) machine.trackedBosses().stream().filter(machine::hasKilled).count();
        TagResolver[] placeholders = {
                Placeholder.unparsed("boss", boss.displayName()),
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("total", String.valueOf(total)),
                Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis())),
                Placeholder.unparsed("run", String.valueOf(machine.runNumber()))};
        announcer.chat("boss-killed", placeholders);
        hud.update();
        if (result == RunStateMachine.BossResult.VICTORY) {
            startVictory();
        } else {
            announcer.title("boss-killed-title", "boss-killed-subtitle", Duration.ofSeconds(3), placeholders);
            announcer.sound("boss-kill");
        }
    }

    private void startVictory() {
        RunRecord record = machine.toRecord(Outcome.VICTORY, null, null);
        store.appendHistory(record);
        save();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        String order = killOrder(record.bossKills());
        String champions = String.join(", ", roster.participants().values());
        TagResolver[] placeholders = {
                Placeholder.unparsed("run", String.valueOf(record.runNumber())),
                Placeholder.unparsed("time", TimeFormat.clock(record.durationMillis())),
                Placeholder.unparsed("order", order),
                Placeholder.unparsed("participants", champions)};
        announcer.title("victory-title", "victory-subtitle", Duration.ofSeconds(6), placeholders);
        announcer.chatAlways("victory", placeholders);
        announcer.sound("victory");
        hud.update();
        postRunEnd(record);

        if (settings.victoryFireworks()) {
            startFireworks(settings.victoryFreezeSeconds());
        }
        cancel(victoryTask);
        victoryTask = Bukkit.getScheduler().runTaskLater(plugin, this::finishVictory, Math.max(1L, settings.victoryFreezeSeconds() * 20L));
    }

    private void finishVictory() {
        cancel(fireworksTask);
        if (machine.phase() != RunPhase.VICTORY) {
            return;
        }
        if (settings.victoryAction() == Settings.VictoryAction.RESET) {
            machine.beginTransition();
            save();
            runTransition("run #" + machine.runNumber() + " was won");
        } else {
            machine.stop();
            hud.hide();
            save();
            announcer.chatAlways("victory-stop");
        }
    }

    private void startFireworks(int seconds) {
        cancel(fireworksTask);
        int bursts = Math.max(1, Math.min(20, seconds * 2 / 3));
        int[] fired = {0};
        fireworksTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (fired[0]++ >= bursts || machine.phase() != RunPhase.VICTORY) {
                cancel(fireworksTask);
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (roster.isParticipant(player.getUniqueId()) && isRunWorld(player.getWorld())) {
                    launchFirework(player.getLocation());
                }
            }
        }, 0L, 30L);
    }

    private void launchFirework(Location location) {
        Color[] palette = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.WHITE};
        FireworkEffect.Type[] types = FireworkEffect.Type.values();
        location.getWorld().spawn(location, Firework.class, firework -> {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(types[random.nextInt(types.length)])
                    .withColor(palette[random.nextInt(palette.length)], palette[random.nextInt(palette.length)])
                    .withFade(palette[random.nextInt(palette.length)])
                    .flicker(random.nextBoolean())
                    .trail(true)
                    .build());
            meta.setPower(1 + random.nextInt(2));
            firework.setFireworkMeta(meta);
        });
    }

    private static String killOrder(List<BossKill> kills) {
        if (kills.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (BossKill kill : kills) {
            parts.add(kill.boss().displayName() + " (" + TimeFormat.clock(kill.elapsedMillis()) + ")");
        }
        return String.join(" → ", parts);
    }

    private static String bossList(List<Boss> bosses) {
        return String.join(", ", bosses.stream().map(Boss::displayName).toList());
    }

    private void postRunEnd(RunRecord record) {
        JsonObject payload = new JsonObject();
        payload.addProperty("event", "run_end");
        payload.addProperty("run", record.runNumber());
        payload.addProperty("outcome", record.outcome().name().toLowerCase(Locale.ROOT));
        payload.addProperty("seed", record.seed());
        payload.addProperty("world", record.worldName());
        payload.addProperty("duration_millis", record.durationMillis());
        JsonArray kills = new JsonArray();
        for (BossKill kill : record.bossKills()) {
            JsonObject k = new JsonObject();
            k.addProperty("boss", kill.boss().id());
            k.addProperty("elapsed_millis", kill.elapsedMillis());
            kills.add(k);
        }
        payload.add("boss_kills", kills);
        StringBuilder content = new StringBuilder("Run #").append(record.runNumber()).append(" ended: ")
                .append(record.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .append(" after ").append(TimeFormat.clock(record.durationMillis()));
        DeathRecord death = record.death();
        if (death != null) {
            JsonObject d = new JsonObject();
            d.addProperty("player", death.playerName());
            d.addProperty("cause", death.cause());
            d.addProperty("killer", death.killer());
            d.addProperty("message", death.message());
            d.addProperty("world", death.world());
            d.addProperty("x", death.x());
            d.addProperty("y", death.y());
            d.addProperty("z", death.z());
            payload.add("death", d);
            content.append(" - ").append(death.message() != null ? death.message() : death.playerName() + " died to " + death.cause());
        }
        if (record.reason() != null) {
            payload.addProperty("reason", record.reason());
        }
        webhook.post(settings.webhookUrl(), content.toString(), payload);
    }

    // ============================================================ connections

    /** Puts a joining player where they belong. The proxy may drop them anywhere, so never assume spawn. */
    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        roster.updateName(id, player.getName());
        hud.show(player);

        RunPhase phase = machine.phase();
        if (phase == RunPhase.IDLE) {
            if (current != null && isRunWorld(player.getWorld())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            return;
        }
        if (current == null) {
            player.setGameMode(GameMode.SPECTATOR);
            return;
        }
        Location spawn = current.spawn();

        if (phase == RunPhase.RUNNING && roster.isActive(id)) {
            if (roster.needsSync(id, machine.runNumber())) {
                // They were away when the run changed: bring them into the new one fresh.
                moveIntoRun(player, spawn);
                save();
            } else if (!isRunWorld(player.getWorld())) {
                player.teleportAsync(spawn);
            }
            return;
        }

        // Spectators: non-participants, eliminated participants, or anyone during a reset / victory.
        player.setGameMode(GameMode.SPECTATOR);
        if (!isRunWorld(player.getWorld())) {
            player.teleportAsync(spawn);
        }
    }

    // ============================================================ participants

    public void addParticipant(CommandSender sender, String name) {
        OfflinePlayer target = resolvePlayer(name);
        if (target == null) {
            sender.sendMessage(messages.chat("unknown-player", Placeholder.unparsed("player", name)));
            return;
        }
        String targetName = target.getName() != null ? target.getName() : name;
        if (!roster.add(target.getUniqueId(), targetName)) {
            sender.sendMessage(messages.chat("participant-already", Placeholder.unparsed("player", targetName)));
            return;
        }
        Player online = target.getPlayer();
        if (online != null && machine.phase() == RunPhase.RUNNING && current != null) {
            moveIntoRun(online, current.spawn());
        }
        save();
        hud.update();
        sender.sendMessage(messages.chat("participant-added", Placeholder.unparsed("player", targetName)));
    }

    public void removeParticipant(CommandSender sender, String name) {
        UUID match = null;
        String matchName = name;
        for (Map.Entry<UUID, String> entry : roster.participants().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                match = entry.getKey();
                matchName = entry.getValue();
            }
        }
        if (match == null) {
            sender.sendMessage(messages.chat("participant-not-found", Placeholder.unparsed("player", name)));
            return;
        }
        roster.remove(match);
        Player online = Bukkit.getPlayer(match);
        if (online != null && machine.phase() != RunPhase.IDLE) {
            online.setGameMode(GameMode.SPECTATOR);
        }
        save();
        hud.update();
        sender.sendMessage(messages.chat("participant-removed", Placeholder.unparsed("player", matchName)));
    }

    public void listParticipants(CommandSender sender) {
        if (roster.isEmpty()) {
            sender.sendMessage(messages.chat("participants-empty"));
            return;
        }
        sender.sendMessage(messages.chat("participants-list",
                Placeholder.component("players", participantNames()),
                Placeholder.unparsed("count", String.valueOf(roster.size()))));
    }

    public List<String> participantNames(boolean includeOnlineNonParticipants) {
        List<String> names = new ArrayList<>();
        if (includeOnlineNonParticipants) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!roster.isParticipant(player.getUniqueId())) {
                    names.add(player.getName());
                }
            }
        } else {
            names.addAll(roster.participants().values());
        }
        return names;
    }

    private static OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private Component participantNames() {
        List<Component> names = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : roster.participants().entrySet()) {
            String key;
            if (roster.isEliminated(entry.getKey())) {
                key = "status-player-out";
            } else if (Bukkit.getPlayer(entry.getKey()) != null) {
                key = "status-player-alive";
            } else {
                key = "status-player-offline";
            }
            names.add(messages.plain(key, Placeholder.unparsed("name", entry.getValue())));
        }
        return Component.join(JoinConfiguration.commas(true), names);
    }

    // ================================================================= reports

    public void sendStatus(CommandSender sender) {
        RunPhase phase = machine.phase();
        String run = String.valueOf(machine.runNumber());
        if (phase == RunPhase.IDLE) {
            sender.sendMessage(messages.chat("status-idle", Placeholder.unparsed("run", run)));
        } else {
            sender.sendMessage(messages.chat("status-header",
                    Placeholder.unparsed("run", run),
                    Placeholder.unparsed("phase", phase.name().toLowerCase(Locale.ROOT))));
        }
        if (machine.runNumber() > 0) {
            sender.sendMessage(messages.plain("status-time",
                    Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis())),
                    Placeholder.unparsed("seed", String.valueOf(machine.seed())),
                    Placeholder.unparsed("world", String.valueOf(machine.worldName()))));
            for (Boss boss : machine.trackedBosses()) {
                Optional<BossKill> kill = machine.bossKills().stream().filter(k -> k.boss() == boss).findFirst();
                if (kill.isPresent()) {
                    sender.sendMessage(messages.plain("status-boss-done",
                            Placeholder.unparsed("boss", boss.displayName()),
                            Placeholder.unparsed("time", TimeFormat.clock(kill.get().elapsedMillis()))));
                } else {
                    sender.sendMessage(messages.plain("status-boss-todo", Placeholder.unparsed("boss", boss.displayName())));
                }
            }
        }
        sender.sendMessage(messages.plain("status-participants",
                Placeholder.component("players", roster.isEmpty() ? messages.plain("none") : participantNames())));
    }

    public void sendHistory(CommandSender sender, int count) {
        List<RunRecord> recent = store.recentHistory(count);
        if (recent.isEmpty()) {
            sender.sendMessage(messages.chat("history-empty"));
            return;
        }
        sender.sendMessage(messages.chat("history-header", Placeholder.unparsed("count", String.valueOf(recent.size()))));
        for (RunRecord record : recent) {
            String outcomeKey = "outcome-" + record.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-');
            sender.sendMessage(messages.plain("history-entry",
                    Placeholder.unparsed("run", String.valueOf(record.runNumber())),
                    Placeholder.component("outcome", messages.plain(outcomeKey)),
                    Placeholder.unparsed("time", TimeFormat.clock(record.durationMillis())),
                    Placeholder.unparsed("seed", String.valueOf(record.seed())),
                    Placeholder.unparsed("bosses", record.bossKills().isEmpty() ? messages.raw("none") : killOrder(record.bossKills())),
                    Placeholder.unparsed("ended", HISTORY_TIME.format(Instant.ofEpochMilli(record.endedAt())))));
            DeathRecord death = record.death();
            if (death != null) {
                sender.sendMessage(messages.plain("history-death",
                        Placeholder.unparsed("player", death.playerName()),
                        Placeholder.unparsed("cause", death.cause()),
                        Placeholder.unparsed("killer", death.killer() == null ? "" : " (" + death.killer() + ")"),
                        Placeholder.unparsed("x", String.valueOf(death.x())),
                        Placeholder.unparsed("y", String.valueOf(death.y())),
                        Placeholder.unparsed("z", String.valueOf(death.z())),
                        Placeholder.unparsed("world", death.world() == null ? "?" : death.world())));
            }
            if (record.reason() != null) {
                sender.sendMessage(messages.plain("history-reason", Placeholder.unparsed("reason", record.reason())));
            }
        }
    }

    // ================================================================= helpers

    /** Where to put people when there is no run world: the server's main world spawn. */
    private static Location fallbackLocation() {
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static void cancel(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    /** State of one reset in progress. */
    private static final class Transition {
        final int oldRun;
        final List<String> oldPaths;
        final int newRun;
        final String reason;
        long deadlineMillis;
        int lastShownSecond = -1;
        BukkitTask countdownTask;
        boolean countdownDone;
        RunWorlds worlds;
        boolean completed;
        boolean cancelled;

        Transition(int oldRun, List<String> oldPaths, int newRun, String reason) {
            this.oldRun = oldRun;
            this.oldPaths = List.copyOf(oldPaths);
            this.newRun = newRun;
            this.reason = reason;
        }

        void cancel() {
            cancelled = true;
            ChallengeManager.cancel(countdownTask);
        }
    }
}
