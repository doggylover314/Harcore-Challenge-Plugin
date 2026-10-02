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
import io.github.doggylover314.hardcorechallenge.core.RunLog;
import io.github.doggylover314.hardcorechallenge.core.RunQuery;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import io.github.doggylover314.hardcorechallenge.core.RunStateMachine;
import io.github.doggylover314.hardcorechallenge.core.TimeFormat;
import io.github.doggylover314.hardcorechallenge.data.DataStore;
import io.github.doggylover314.hardcorechallenge.data.RunArchive;
import io.github.doggylover314.hardcorechallenge.tracking.RunTracker;
import io.github.doggylover314.hardcorechallenge.ui.RunReports;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
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
    private final HardcoreChallengePlugin plugin;
    private final Logger logger;
    private final DataStore store;
    private final WorldService worlds;
    private final RunStateMachine machine;
    private final Webhook webhook;
    private final RunArchive archive;
    private final RunTracker tracker;
    private final RunReports reports;
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
    /** Log of the run being played (null when no run is being played). */
    private RunLog live;
    /** Pending "/hcc run <n> delete" confirmations: sender name -> run number and expiry. */
    private final Map<String, long[]> pendingDeletes = new HashMap<>();

    public ChallengeManager(HardcoreChallengePlugin plugin, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.messages = messages;
        this.store = new DataStore(plugin.getDataPath(), logger);
        this.worlds = new WorldService(plugin, store, () -> currentPaths);
        this.machine = new RunStateMachine(() -> System.nanoTime() / 1_000_000L, System::currentTimeMillis);
        this.webhook = new Webhook(logger);
        this.archive = new RunArchive(plugin.getDataPath(), store, logger);
        this.tracker = new RunTracker(this);
        this.reports = new RunReports(() -> this.messages, run -> machine.elapsedMillis());
    }

    // =================================================================== lifecycle

    public void enable() {
        DataStore.PersistedState state = store.loadState();
        archive.load();
        store.loadPendingDeletions();

        machine.restore(state.run());
        roster = state.roster();
        currentPaths = List.copyOf(state.worldPaths());
        configureMachine();

        announcer = new Announcer(settings, messages, logger);
        hud = new Hud(machine, this::aliveParticipantCount, roster::size, settings, messages);

        resume();
        recoverRunLogs();
        worlds.processPendingDeletions();
        updateClock();

        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
    }

    private void resume() {
        RunPhase phase = machine.phase();
        int run = machine.runNumber();
        switch (phase) {
            case RUNNING, VICTORY -> {
                Optional<RunWorlds> loaded = worlds.load(run, machine.seed(), currentPaths);
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
                Bukkit.getScheduler().runTask(plugin, () -> runTransition("resuming after a restart", SeedChoice.RANDOM));
            }
            case IDLE -> {
                // Keep the last world around for sightseeing if it still exists.
                if (run > 0 && !currentPaths.isEmpty() && currentPaths.stream().allMatch(p -> Files.isDirectory(Paths.get(p)))) {
                    worlds.load(run, machine.seed(), currentPaths)
                            .ifPresent(loaded -> current = loaded);
                }
            }
        }
        save();
    }

    public void disable() {
        if (machine.hasPendingDeath()) {
            // A death was waiting for the next tick; it stands.
            machine.resolvePendingDeath(Long.MAX_VALUE).ifPresent(death -> endLog(Outcome.DEATH, death, null));
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
        if (live != null) {
            tracker.sample();
            archive.saveNow(live);
        }
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
        updateClock();
        tracker.sample();
        hud.update();
        // Checkpoint the clock periodically so a crash loses at most a minute of run time.
        if (machine.phase() == RunPhase.RUNNING && Bukkit.getCurrentTick() % 1200 < 20) {
            save();
            if (live != null) {
                archive.save(live);
            }
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
        start(sender, SeedChoice.RANDOM);
    }

    /** /hcc start replay <run> */
    public void startReplay(CommandSender sender, int runNumber) {
        RunLog past = archive.get(runNumber);
        if (past == null) {
            sender.sendMessage(messages.chat("run-not-found", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        start(sender, new SeedChoice(past.seed(), runNumber, false));
    }

    /** /hcc start seed <seed>. Text that isn't a number is hashed like vanilla does. */
    public void startWithSeed(CommandSender sender, String rawSeed) {
        long seed;
        try {
            seed = Long.parseLong(rawSeed.trim());
        } catch (NumberFormatException e) {
            seed = rawSeed.trim().hashCode();
        }
        start(sender, new SeedChoice(seed, null, true));
    }

    private void start(CommandSender sender, SeedChoice choice) {
        switch (machine.phase()) {
            case RUNNING, VICTORY -> sender.sendMessage(messages.chat("run-already-active"));
            case RESETTING -> sender.sendMessage(messages.chat("transition-in-progress"));
            case IDLE -> {
                if (!ensureParticipants(sender)) {
                    return;
                }
                machine.beginTransition();
                save();
                runTransition(messages.raw("default-reset-reason"), choice);
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
            postRunEnd(endLog(Outcome.FORCED_RESET, null, why));
        }
        announcer.chat("reset-forced", Placeholder.unparsed("player", sender.getName()), Placeholder.unparsed("reason", why));
        machine.beginTransition();
        save();
        runTransition(why, SeedChoice.RANDOM);
    }

    /** /hcc stop */
    public void stop(CommandSender sender) {
        RunPhase phase = machine.phase();
        if (phase == RunPhase.IDLE) {
            sender.sendMessage(messages.chat("run-not-active"));
            return;
        }
        if (phase == RunPhase.RUNNING) {
            postRunEnd(endLog(Outcome.STOPPED, null, "stopped by " + sender.getName()));
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
    private void runTransition(String reason, SeedChoice choice) {
        cancelTransition();
        cancel(victoryTask);
        cancel(fireworksTask);

        int newRun = machine.runNumber() + 1;
        while (worlds.isRunNumberTaken(newRun)) {
            newRun++;
        }
        long seed = choice.seed() != null ? choice.seed() : newSeed(machine.seed());
        transition = new Transition(machine.runNumber(), currentPaths, newRun, reason, choice);

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
        worlds.create(newRun, seed).whenComplete((created, error) -> {
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
        if (t.choice.seed() != null && t.choice.seed() != machine.seed()) {
            logger.warning("Run #" + t.newRun + " reused an existing world folder, so its seed is " + machine.seed()
                    + " instead of the requested " + t.choice.seed());
        }
        live = new RunLog(t.newRun, machine.seed(), machine.worldName(), machine.startedAt(), t.choice.replayOf(), t.choice.custom());
        roster.participants().forEach(live::addParticipant);
        tracker.reset();
        logEvent(TimelineEvent.Type.RUN_STARTED, null, null);
        save();

        Location spawn = next.spawn();
        List<CompletableFuture<?>> moves = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            moves.add(moveIntoRun(player, spawn));
        }

        hud.showAll();
        announceRunStart();
        updateClock();

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
            logEvent(TimelineEvent.Type.ELIMINATED, player.getName(), death.cause());
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

    /** Disconnecting never ends a run; it only shows up in the timeline and may pause the clock. */
    public void handleQuit(Player player) {
        if (machine.phase() == RunPhase.RUNNING && roster.isParticipant(player.getUniqueId())) {
            tracker.sample();
            logEvent(TimelineEvent.Type.LEFT, player.getName(), null);
        }
        tracker.forget(player.getUniqueId());
        // The player still counts as online during the quit event.
        Bukkit.getScheduler().runTask(plugin, this::updateClock);
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
        RunLog record = endLog(Outcome.DEATH, death, null);
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
        announcer.chatAlways("death", placeholders);
        announcer.title("death-title", "death-subtitle", Duration.ofSeconds(3), placeholders);
        announcer.sound("death");
        postRunEnd(record);

        runTransition(death.playerName() + " died", SeedChoice.RANDOM);
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

    public void handleBossDeath(Boss boss, World world, Player killer) {
        if (!isRunWorld(world)) {
            return;
        }
        RunStateMachine.BossResult result = machine.reportBossKill(boss, Bukkit.getCurrentTick());
        if (result != RunStateMachine.BossResult.RECORDED && result != RunStateMachine.BossResult.VICTORY) {
            return;
        }
        if (live != null) {
            boolean credited = killer != null && roster.isParticipant(killer.getUniqueId());
            live.bossKilled(boss, machine.elapsedMillis(),
                    credited ? killer.getUniqueId() : null, credited ? killer.getName() : null);
            logEvent(TimelineEvent.Type.BOSS_KILLED, credited ? killer.getName() : null, boss.id());
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
        RunLog record = endLog(Outcome.VICTORY, null, null);
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
            runTransition("run #" + machine.runNumber() + " was won", SeedChoice.RANDOM);
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

    private void postRunEnd(RunLog record) {
        if (record == null) {
            return;
        }
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

        if (phase == RunPhase.RUNNING && roster.isParticipant(id)) {
            logEvent(TimelineEvent.Type.JOINED, player.getName(), null);
            updateClock();
        }
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
        if (machine.phase() == RunPhase.RUNNING && live != null) {
            live.addParticipant(target.getUniqueId(), targetName);
            logEvent(TimelineEvent.Type.PARTICIPANT_ADDED, targetName, null);
        }
        if (online != null && machine.phase() == RunPhase.RUNNING && current != null) {
            moveIntoRun(online, current.spawn());
        }
        updateClock();
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
        if (machine.phase() == RunPhase.RUNNING && live != null) {
            logEvent(TimelineEvent.Type.PARTICIPANT_REMOVED, matchName, null);
        }
        updateClock();
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

    /** /hcc runs [filter] [page] */
    public void sendRuns(CommandSender sender, Outcome outcome, String player, int page) {
        List<RunLog> runs = RunQuery.filter(archive.all(), outcome, player);
        String base = "/hcc runs";
        String label = messages.raw("runs-filter-all");
        if (outcome != null) {
            String name = outcomeFilterName(outcome);
            base += " " + name;
            label = name;
        } else if (player != null) {
            base += " player " + player;
            label = player;
        }
        reports.sendList(sender, runs, page, base, label);
    }

    /** The /hcc runs filter word for an outcome. */
    public static String outcomeFilterName(Outcome outcome) {
        return switch (outcome) {
            case VICTORY -> "wins";
            case DEATH -> "deaths";
            case FORCED_RESET -> "resets";
            case STOPPED -> "stopped";
        };
    }

    /** /hcc run <n> */
    public void sendRun(CommandSender sender, int runNumber) {
        RunLog run = archive.get(runNumber);
        if (run == null) {
            sender.sendMessage(messages.chat("run-not-found", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        reports.sendDetail(sender, run);
    }

    /** /hcc run <n> timeline [page] */
    public void sendTimeline(CommandSender sender, int runNumber, int page) {
        RunLog run = archive.get(runNumber);
        if (run == null) {
            sender.sendMessage(messages.chat("run-not-found", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        reports.sendTimeline(sender, run, page);
    }

    /** /hcc run <n> delete: asks for confirmation. */
    public void requestDelete(CommandSender sender, int runNumber) {
        RunLog run = archive.get(runNumber);
        if (run == null) {
            sender.sendMessage(messages.chat("run-not-found", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        if (run.isLive()) {
            sender.sendMessage(messages.chat("run-delete-live"));
            return;
        }
        pendingDeletes.put(sender.getName(), new long[] {runNumber, System.currentTimeMillis() + 30_000L});
        sender.sendMessage(messages.chat("run-delete-confirm", Placeholder.unparsed("run", String.valueOf(runNumber)))
                .append(Component.space())
                .append(messages.plain("run-delete-button")
                        .clickEvent(ClickEvent.runCommand("/hcc run " + runNumber + " delete confirm"))));
    }

    /** /hcc run <n> delete confirm */
    public void confirmDelete(CommandSender sender, int runNumber) {
        long[] pending = pendingDeletes.remove(sender.getName());
        if (pending == null || pending[0] != runNumber || pending[1] < System.currentTimeMillis()) {
            sender.sendMessage(messages.chat("run-delete-expired", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        RunLog run = archive.get(runNumber);
        if (run == null || run.isLive() || !archive.delete(runNumber)) {
            sender.sendMessage(messages.chat("run-not-found", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        sender.sendMessage(messages.chat("run-deleted", Placeholder.unparsed("run", String.valueOf(runNumber))));
    }

    /** Run numbers in the list, newest first, for tab completion. */
    public List<Integer> runNumbers() {
        List<Integer> numbers = new ArrayList<>();
        for (RunLog run : RunQuery.filter(archive.all(), null, null)) {
            numbers.add(run.runNumber());
        }
        return numbers;
    }

    /** Names of everyone who has taken part in a run, for tab completion. */
    public List<String> knownPlayerNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (RunLog run : archive.all()) {
            names.addAll(run.participants().values());
        }
        return new ArrayList<>(names);
    }

    // ================================================================= run log

    public RunTracker tracker() {
        return tracker;
    }

    public RunLog liveRun() {
        return live;
    }

    /** Adds an entry to the live run's timeline. */
    public void logEvent(TimelineEvent.Type type, String player, String detail) {
        if (live == null) {
            return;
        }
        live.event(new TimelineEvent(machine.elapsedMillis(), System.currentTimeMillis(), type, player, detail));
        archive.save(live);
    }

    /** Finishes the live run's log and stores it. Returns the finished log, or null if there was none. */
    private RunLog endLog(Outcome outcome, DeathRecord death, String reason) {
        if (live == null) {
            return null;
        }
        tracker.sample();
        long now = System.currentTimeMillis();
        if (death != null) {
            live.event(new TimelineEvent(machine.elapsedMillis(), now, TimelineEvent.Type.DEATH, death.playerName(), death.cause()));
        }
        live.event(new TimelineEvent(machine.elapsedMillis(), now, TimelineEvent.Type.RUN_ENDED, null,
                outcome.name().toLowerCase(Locale.ROOT)));
        live.finish(outcome, now, machine.elapsedMillis(), death, reason);
        RunLog finished = live;
        live = null;
        archive.save(finished);
        return finished;
    }

    /**
     * After a restart: pick up the log of the run being resumed, and close logs left open by a crash.
     */
    private void recoverRunLogs() {
        int current = machine.runNumber();
        for (RunLog run : new ArrayList<>(archive.all())) {
            if (!run.isLive()) {
                continue;
            }
            if (run.runNumber() == current && machine.phase() == RunPhase.RUNNING) {
                live = run;
            } else {
                run.finish(Outcome.STOPPED, System.currentTimeMillis(), run.durationMillis(), null, "server stopped unexpectedly");
                archive.save(run);
            }
        }
        if (live == null && machine.phase() == RunPhase.RUNNING) {
            live = new RunLog(current, machine.seed(), machine.worldName(), machine.startedAt(), null, false);
            roster.participants().forEach(live::addParticipant);
            archive.save(live);
        }
    }

    /** The run clock only runs while at least one participant who is still in the run is online. */
    private void updateClock() {
        if (machine.phase() != RunPhase.RUNNING) {
            return;
        }
        boolean anyoneOn = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (roster.isActive(player.getUniqueId())) {
                anyoneOn = true;
                break;
            }
        }
        if (anyoneOn) {
            machine.resumeClock();
        } else if (machine.clockRunning()) {
            machine.pauseClock();
            save();
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

    /** How the next run's seed is chosen: random, a past run's seed, or one an admin typed. */
    private record SeedChoice(Long seed, Integer replayOf, boolean custom) {
        static final SeedChoice RANDOM = new SeedChoice(null, null, false);
    }

    /** State of one reset in progress. */
    private static final class Transition {
        final int oldRun;
        final List<String> oldPaths;
        final int newRun;
        final String reason;
        final SeedChoice choice;
        long deadlineMillis;
        int lastShownSecond = -1;
        BukkitTask countdownTask;
        boolean countdownDone;
        RunWorlds worlds;
        boolean completed;
        boolean cancelled;

        Transition(int oldRun, List<String> oldPaths, int newRun, String reason, SeedChoice choice) {
            this.choice = choice;
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
