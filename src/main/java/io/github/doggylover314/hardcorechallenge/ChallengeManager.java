package io.github.doggylover314.hardcorechallenge;

import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.config.Settings;
import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.BossKill;
import io.github.doggylover314.hardcorechallenge.core.DeathRecord;
import io.github.doggylover314.hardcorechallenge.core.Outcome;
import io.github.doggylover314.hardcorechallenge.core.ResetRule;
import io.github.doggylover314.hardcorechallenge.core.Restore;
import io.github.doggylover314.hardcorechallenge.core.Roster;
import io.github.doggylover314.hardcorechallenge.core.RunEnd;
import io.github.doggylover314.hardcorechallenge.core.RunNumbers;
import io.github.doggylover314.hardcorechallenge.core.RunRecap;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.RunLog;
import io.github.doggylover314.hardcorechallenge.core.RunQuery;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import io.github.doggylover314.hardcorechallenge.core.RunStateMachine;
import io.github.doggylover314.hardcorechallenge.core.SafeSpot;
import io.github.doggylover314.hardcorechallenge.core.SeedChoice;
import io.github.doggylover314.hardcorechallenge.core.SeedList;
import io.github.doggylover314.hardcorechallenge.core.Seeds;
import io.github.doggylover314.hardcorechallenge.core.Spot;
import io.github.doggylover314.hardcorechallenge.core.TimeFormat;
import io.github.doggylover314.hardcorechallenge.data.DataStore;
import io.github.doggylover314.hardcorechallenge.data.RunArchive;
import io.github.doggylover314.hardcorechallenge.tracking.RunTracker;
import io.github.doggylover314.hardcorechallenge.ui.RunReports;
import io.github.doggylover314.hardcorechallenge.player.InventorySnapshot;
import io.github.doggylover314.hardcorechallenge.player.PlayerResetter;
import io.github.doggylover314.hardcorechallenge.player.SpawnProtection;
import io.github.doggylover314.hardcorechallenge.ui.Announcer;
import io.github.doggylover314.hardcorechallenge.ui.Hud;
import io.github.doggylover314.hardcorechallenge.world.BlockTerrain;
import io.github.doggylover314.hardcorechallenge.world.RunWorlds;
import io.github.doggylover314.hardcorechallenge.world.WorldService;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the challenge: turns deaths, boss kills and commands into state machine transitions and
 * carries out the side effects (worlds, players, announcements, persistence).
 *
 * <p>Everything here runs on the main server thread.</p>
 */
public final class ChallengeManager {
    /** A reset that has not produced a playable world after this long is given up on. */
    private static final int TRANSITION_TIMEOUT_SECONDS = 120;

    private final HardcoreChallengePlugin plugin;
    private final Logger logger;
    private final DataStore store;
    private final WorldService worlds;
    private final RunStateMachine machine;
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
    /** Seed request of the reset in progress; persisted while RESETTING so a restart keeps it. */
    private SeedChoice pendingChoice = SeedChoice.RANDOM;
    /** Seed request found in state.yml on startup, used if the server stopped during a reset. */
    private SeedChoice restoredChoice = SeedChoice.RANDOM;
    /** Where a cycle-mode seed list stands (index of the next entry); persisted in state.yml. */
    private int seedPosition;
    /**
     * Index of the list entry the reset in progress took, -1 if none or {@link SeedList#DROPPED} if an edit removed
     * it; follows edits to the list. Persisted in state.yml.
     */
    private int seedPicked = -1;
    /** Entry of a run that started while config.yml had an error: still to be used up. Persisted in state.yml. */
    private SeedList.Unconsumed unconsumed;
    /** Number of seeds in the list as last read, for tab completion. */
    private int seedCount;
    private BukkitTask victoryTask;
    private BukkitTask fireworksTask;
    private BukkitTask ticker;
    private boolean resolveScheduled;
    /** state.yml exists but could not be read: nothing is deleted, saved over it or started until it is fixed. */
    private boolean stateUnreadable;
    /** Vanilla death message of the pending death, shown when it resolves. */
    private Component pendingDeathMessage;
    /** Log of the run being played (null when no run is being played). */
    private RunLog live;
    /** Whether the live log has changes that are not on disk yet. */
    private boolean liveDirty;
    /** The live log is only kept in memory because the run's file on disk must not be overwritten. */
    private boolean liveDetached;
    private long lastLiveFlush;
    /** Pending "/hcc run <n> delete" confirmations: player UUID (or sender name for the console and RCON) -> request. */
    private final Map<String, PendingDelete> pendingDeletes = new HashMap<>();

    private record PendingDelete(int run, long expiresAt) {
    }

    private static final long DELETE_CONFIRM_MILLIS = 30_000L;
    /** Timeline events are written to disk in batches at most this often. */
    private static final long LIVE_FLUSH_MILLIS = 15_000L;
    /** Eliminations of this run, most recent first; used if a rule change ends the run afterwards. */
    private final List<Elimination> eliminations = new ArrayList<>();

    private record Elimination(DeathRecord death, Component message) {
    }

    private final SpawnProtection protection = new SpawnProtection(() -> System.nanoTime() / 1_000_000L);

    /** Deaths, positions and restores that let a run that ended be continued; persisted in state.yml. */
    private RunEnd runEnd = new RunEnd();
    /** Marks the items and experience dropped at a death spot with the tag of that death, so continuing can remove them. */
    private final NamespacedKey dropKey;

    public ChallengeManager(HardcoreChallengePlugin plugin, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dropKey = new NamespacedKey(plugin, "death-drop");
        this.settings = settings;
        this.messages = messages;
        this.store = new DataStore(plugin.getDataPath(), logger);
        this.archive = new RunArchive(plugin.getDataPath(), store, logger);
        this.worlds = new WorldService(plugin, store, () -> currentPaths, archive::hasFile);
        this.machine = new RunStateMachine(() -> System.nanoTime() / 1_000_000L, System::currentTimeMillis);
        this.tracker = new RunTracker(this);
        this.reports = new RunReports(() -> this.messages, run -> machine.elapsedMillis());
    }

    // =================================================================== lifecycle

    public void enable() {
        DataStore.PersistedState state = store.loadState();
        stateUnreadable = state.unreadable();
        archive.load();
        store.loadPendingDeletions();

        machine.restore(state.run());
        roster = state.roster();
        runEnd = state.runEnd();
        currentPaths = List.copyOf(state.worldPaths());
        restoredChoice = state.pendingSeed() != null ? state.pendingSeed() : SeedChoice.RANDOM;
        configureMachine();
        seedPosition = state.seedPosition();
        seedPicked = restoredChoice.fromList() ? state.seedPicked() : -1;
        unconsumed = state.unconsumedSeed();
        keepSeedPositionInList();

        announcer = new Announcer(settings, messages, logger);
        hud = new Hud(machine, roster::aliveInRun, roster::runSize, settings, messages);

        resume();
        if (stateUnreadable) {
            // The real run may still be on disk; with an empty state its worlds would look like leftovers.
            logger.severe("state.yml could not be read, so the current run is unknown. Not cleaning up any world folders,"
                    + " closing run logs or saving state, and /hcc start is disabled. Fix or restore state.yml"
                    + " (a copy was kept as state.yml.broken) and restart the server.");
        } else {
            recoverRunLogs();
            worlds.sweepLeftovers(machine.runNumber());
            worlds.processPendingDeletions(settings.keepOldWorlds());
        }
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
                SeedChoice choice = restoredChoice;
                pendingChoice = choice;
                // Keep the old worlds loaded like in a normal reset, so joins and /hcc continue find players in place.
                RunWorlds old = run > 0 ? loadOldWorlds(run) : null;
                if (old != null) {
                    current = old;
                    currentPaths = old.paths();
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // An admin may have stopped or continued the run in the meantime.
                    if (machine.phase() == RunPhase.RESETTING && transition == null) {
                        runTransition(reasonText("reason-restart"), choice);
                    }
                });
            }
            case IDLE -> {
                // Keep the last world around for sightseeing if it still exists.
                if (run > 0 && !currentPaths.isEmpty() && currentPaths.stream().allMatch(p -> Files.isDirectory(Paths.get(p)))) {
                    worlds.load(run, machine.seed(), currentPaths).ifPresent(loaded -> {
                        current = loaded;
                        // The real folders of the loaded worlds, not whatever absolute paths were stored.
                        currentPaths = loaded.paths();
                    });
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
        if (!stateUnreadable) {
            store.saveStateNow(persistedState());
        }
        if (live != null) {
            tracker.sample(live);
            live.checkpoint(machine.elapsedMillis(), System.currentTimeMillis());
            if (!liveDetached) {
                archive.saveNow(live);
            }
        }
    }

    /** Re-reads config.yml. False if it has an error: the current settings stay then. */
    public boolean reload() {
        if (!reloadConfigFile()) {
            return false;
        }
        settings = Settings.load(plugin.getConfig(), logger);
        messages = new Messages(plugin.getConfig(), logger);
        configureMachine();
        followSeedMode(settings.seedMode());
        int position = seedPosition;
        keepSeedPositionInList();
        if (position != seedPosition) {
            save();
        }
        announcer.reload(settings, messages);
        hud.reload(settings, messages);
        if (machine.reevaluateVictory()) {
            startVictory();
        }
        enforceResetRule();
        return true;
    }

    private void configureMachine() {
        machine.configure(settings.bosses(), settings.victoryRequireAllBosses(), settings.victoryEnabled());
    }

    private void tickSecond() {
        updateClock();
        tracker.sample();
        hud.update();
        flushLive(false);
        showSpawnProtection();
        // Checkpoint the clock periodically so a crash loses at most a minute of run time.
        if (machine.phase() == RunPhase.RUNNING && Bukkit.getCurrentTick() % 1200 < 20) {
            save();
            flushLive(true);
        }
    }

    private void showSpawnProtection() {
        boolean show = machine.phase() == RunPhase.RUNNING && !messages.isBlank("spawn-protection-actionbar");
        for (UUID id : protection.active()) {
            Player player = Bukkit.getPlayer(id);
            if (show && player != null) {
                player.sendActionBar(messages.plain("spawn-protection-actionbar",
                        Placeholder.unparsed("seconds", String.valueOf(protection.remainingSeconds(id)))));
            }
        }
    }

    private DataStore.PersistedState persistedState() {
        SeedChoice pending = machine.phase() == RunPhase.RESETTING ? pendingChoice : null;
        return new DataStore.PersistedState(machine.snapshot(), currentPaths, roster, pending, seedPosition, seedPicked, unconsumed,
                false, runEnd);
    }

    private void save() {
        if (stateUnreadable) {
            return;
        }
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

    /** Whether the player takes no damage right now because they just entered the run. */
    public boolean isSpawnProtected(UUID id) {
        return machine.phase() == RunPhase.RUNNING && protection.isProtected(id);
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
        start(sender, new SeedChoice(Seeds.parse(rawSeed), null, true));
    }

    private void start(CommandSender sender, SeedChoice choice) {
        switch (machine.phase()) {
            case RUNNING, VICTORY -> sender.sendMessage(messages.chat("run-already-active"));
            case RESETTING -> sender.sendMessage(messages.chat("transition-in-progress"));
            case IDLE -> {
                if (stateUnreadable) {
                    sender.sendMessage(messages.chat("state-broken"));
                    return;
                }
                if (!takeOnlinePlayers(sender)) {
                    return;
                }
                runEnd.clear();
                // The roster was just rebuilt from who is online, so this run can't be continued any more.
                runEnd.markStartedOver();
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
        boolean hasReason = reason != null && !reason.isBlank();
        String why = hasReason ? reason : reasonText("reason-forced", Placeholder.unparsed("player", sender.getName()));
        cancel(victoryTask);
        cancel(fireworksTask);
        RunLog ended = null;
        if (phase == RunPhase.RUNNING) {
            ended = endLog(Outcome.FORCED_RESET, null, why);
        }
        if (hasReason) {
            announcer.chat("reset-forced", Placeholder.unparsed("player", sender.getName()), Placeholder.unparsed("reason", why));
        } else {
            announcer.chat("reset-forced-no-reason", Placeholder.unparsed("player", sender.getName()));
        }
        announceRecap(ended);
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
            recordPositions();
            endLog(Outcome.STOPPED, null, reasonText("reason-stopped", Placeholder.unparsed("player", sender.getName())));
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
    private void runTransition(String reason, SeedChoice requested) {
        cancelTransition();
        cancel(victoryTask);
        cancel(fireworksTask);
        protection.clear();
        recordPositions();

        // Never reuse a number that the state file, the run archive or a folder on disk already knows.
        int highestArchived = archive.highestRunNumber();
        int newRun = RunNumbers.next(machine.runNumber(), highestArchived, worlds.highestRunNumberOnDisk());
        while (worlds.isRunNumberTaken(newRun)) {
            newRun++;
        }
        // A run that would get a random seed takes the next one from the seed list, if there is one.
        // The entry is only used up once the run has started.
        SeedChoice choice = requested.seed() == null ? nextListSeed().orElse(requested) : requested;
        long seed = choice.seed() != null ? choice.seed() : newSeed(machine.seed());
        transition = new Transition(machine.runNumber(), currentPaths, newRun, reason, choice);
        pendingChoice = choice;
        save();

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
        t.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                countdownTick(t);
            } catch (RuntimeException e) {
                failTransition(t, e);
            }
        }, 0L, 5L);
        // Safety net: if the world never becomes ready (a spawn search that hangs, say), give up
        // instead of sitting in RESETTING forever.
        t.watchdog = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!t.cancelled && !t.completed && t.worlds == null) {
                failTransition(t, new IllegalStateException("the new world was not ready after " + TRANSITION_TIMEOUT_SECONDS + " seconds"));
            }
        }, TRANSITION_TIMEOUT_SECONDS * 20L);
        t.creation = worlds.create(newRun, seed);
        t.creation.whenComplete((created, error) -> {
            // Exceptions thrown here would vanish into the future, so handle them ourselves.
            try {
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
            } catch (RuntimeException e) {
                failTransition(t, e);
            }
        });
    }

    private void countdownTick(Transition t) {
        if (t.cancelled) {
            return;
        }
        long now = System.currentTimeMillis();
        long remainingMillis = t.deadlineMillis - now;
        if (remainingMillis <= 0) {
            t.countdownDone = true;
            tryCompleteTransition(t);
            if (t.completed || t.cancelled) {
                cancel(t.countdownTask);
                return;
            }
            // Countdown is over but the world is not ready: keep telling people instead of a blank screen.
            if (now - t.lastPreparingMillis >= 1000L) {
                t.lastPreparingMillis = now;
                announcer.preparing(Placeholder.unparsed("reason", t.reason));
            }
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
        pendingChoice = SeedChoice.RANDOM;
        cancel(t.countdownTask);
        cancel(t.watchdog);
        try {
            completeTransition(t);
        } catch (RuntimeException e) {
            // Never leave a half-started run behind without saying so.
            failTransition(t, e);
            return;
        }
        useListSeed(t.choice);
    }

    private void completeTransition(Transition t) {
        RunWorlds next = t.worlds;
        machine.beginRun(t.newRun, next.overworld().getName(), next.overworld().getSeed());
        t.started = true;
        runEnd.clear();
        logger.info("Run #" + t.newRun + " started in " + next.overworld().getName() + " (seed " + machine.seed() + ")");
        current = next;
        currentPaths = next.paths();
        if (t.oldRun > 0 && t.oldRun != t.newRun) {
            // The old folders only live in memory until every teleport is done; queue them now so a
            // stop right after the reset cannot orphan them. The current worlds stay protected.
            worlds.queueDeletion(t.oldPaths);
        }
        roster.beginRun();
        eliminations.clear();
        if (t.choice.seed() != null && t.choice.seed() != machine.seed()) {
            logger.warning("Run #" + t.newRun + " reused an existing world folder, so its seed is " + machine.seed()
                    + " instead of the requested " + t.choice.seed());
        }
        live = new RunLog(t.newRun, machine.seed(), machine.worldName(), machine.startedAt(), t.choice.replayOf(), t.choice.custom(),
                t.choice.fromList());
        tracker.reset();
        logEvent(TimelineEvent.Type.RUN_STARTED, null, null);
        save();

        // The new overworld ticked while its spawn was searched; every run starts in the morning, clear.
        next.overworld().setFullTime(0L);
        next.overworld().setStorm(false);
        next.overworld().setThundering(false);

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
                    try {
                        save();
                        if (t.oldRun > 0 && t.oldRun != machine.runNumber()) {
                            worlds.retire(t.oldRun, t.oldPaths, current != null ? current.spawn() : fallbackLocation(), settings.keepOldWorlds());
                        }
                    } catch (RuntimeException e) {
                        logger.log(java.util.logging.Level.SEVERE, "Could not finish the move into run #" + t.newRun, e);
                    }
                }));
    }

    /**
     * Teleports a player to the run spawn. Participants join the run (and its log), are wiped, and are
     * set to survival with spawn protection on arrival.
     */
    private CompletableFuture<?> moveIntoRun(Player player, Location spawn) {
        UUID id = player.getUniqueId();
        boolean participant = roster.isActive(id);
        int run = machine.runNumber();
        if (participant) {
            roster.markSynced(id, run);
            roster.joinRun(id);
            if (live != null) {
                live.addParticipant(id, player.getName());
                liveDirty = true;
            }
            // Clear straight away so nothing from the old world travels, even if the teleport is slow.
            PlayerResetter.wipe(player);
            protection.grant(id, settings.spawnProtectionSeconds());
        }
        return player.teleportAsync(spawn).handle((success, error) -> {
            if (error != null) {
                logger.log(java.util.logging.Level.WARNING, "Could not teleport " + player.getName() + " into run #" + run, error);
            }
            onMain(() -> {
                if (!player.isOnline() || machine.runNumber() != run) {
                    return;
                }
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.teleport(spawn);
                }
                if (participant && roster.isActive(id) && machine.phase() == RunPhase.RUNNING) {
                    PlayerResetter.prepareForRun(player);
                    protection.grant(id, settings.spawnProtectionSeconds());
                } else {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            });
            return null;
        });
    }

    /** Abandons a reset in progress, cleaning up any worlds it already created. */
    private void cancelTransition() {
        Transition t = transition;
        transition = null;
        if (t == null || t.completed) {
            return;
        }
        seedPicked = -1;
        t.abort();
        if (t.worlds != null) {
            worlds.retire(t.newRun, t.worlds.paths(), fallbackLocation(), 0);
        }
    }

    private void failTransition(Transition t, Throwable error) {
        logger.log(java.util.logging.Level.SEVERE, "Could not create run #" + t.newRun, error);
        t.abort();
        if (transition == t) {
            transition = null;
        }
        pendingChoice = SeedChoice.RANDOM;
        seedPicked = -1;
        if (t.started) {
            // The run had begun: close its log and clean up the old worlds like a normal reset would.
            endLog(Outcome.STOPPED, null, reasonText("reason-start-failed"));
            if (t.oldRun > 0 && t.oldRun != t.newRun) {
                worlds.retire(t.oldRun, t.oldPaths, current != null ? current.spawn() : fallbackLocation(), settings.keepOldWorlds());
            }
        } else {
            // Retire whatever was created for the run (found by name); nothing may stay loaded.
            worlds.retire(t.newRun, List.of(), fallbackLocation(), 0);
        }
        machine.stop();
        hud.hide();
        save();
        String why = error.getMessage() != null ? error.getMessage() : error.toString();
        announcer.chatAlways("reset-failed", Placeholder.unparsed("error", why));
    }

    private void announceRunStart() {
        TagResolver[] placeholders = {
                Placeholder.unparsed("run", String.valueOf(machine.runNumber())),
                Placeholder.unparsed("seed", String.valueOf(machine.seed())),
                Placeholder.unparsed("bosses", bossList(settings.bosses()))};
        announcer.chat("run-started", placeholders);
        announcer.title("run-started-title", "run-started-subtitle", Duration.ofSeconds(3), placeholders);
        announcer.sound("run-start");
    }

    private long newSeed(long previous) {
        long seed;
        do {
            seed = random.nextLong();
        } while (seed == previous || seed == 0L);
        return seed;
    }

    /** Everyone online (with hardcorechallenge.play) is in the challenge; anyone who joins later is added. */
    private boolean takeOnlinePlayers(CommandSender sender) {
        List<? extends Player> players = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(HardcoreChallengePlugin.PERMISSION_PLAY))
                .toList();
        if (players.isEmpty()) {
            // The roster stays as it is, so the run that ended can still be continued.
            sender.sendMessage(messages.chat("no-players"));
            return false;
        }
        roster.clear();
        players.forEach(player -> roster.add(player.getUniqueId(), player.getName()));
        return true;
    }

    // ==================================================================== deaths

    /** Called for every player death (listener runs at HIGHEST, ignoring already-cancelled events). */
    public void handleDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        RunPhase phase = machine.phase();
        if (phase == RunPhase.IDLE) {
            return;
        }
        // An active participant's death counts wherever it happens (e.g. after a failed teleport).
        boolean counts = phase == RunPhase.RUNNING && roster.isActive(player.getUniqueId());
        if (!counts && !isRunWorld(player.getWorld())) {
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
        if (!counts) {
            return;
        }

        DeathRecord death = describeDeath(player, event, vanillaMessage);
        // Recorded before anything is dropped or cleared, to give back if the run is continued. With first-death
        // the player keeps their inventory as a spectator, so only the spot is needed.
        boolean firstDeath = settings.resetWhen().firstDeath();
        // Unique per death, so a later death of the same player never removes the drops of an earlier one.
        String dropTag = machine.runNumber() + ":" + player.getUniqueId() + ":" + System.currentTimeMillis();
        runEnd.recordDeath(player.getUniqueId(), firstDeath
                ? Restore.dead(spotOf(player.getLocation()), null, 0)
                : Restore.dead(spotOf(player.getLocation()), InventorySnapshot.capture(player.getInventory()),
                        player.calculateTotalExperiencePoints(), dropTag));
        if (firstDeath) {
            // Logged now, so it stays in the timeline even if a victory in the same tick overrides it.
            logEvent(TimelineEvent.Type.DEATH, player.getName(), death.message() != null && !death.message().isBlank() ? death.message() : death.cause());
            reportRunEndingDeath(death, vanillaMessage);
            return;
        }

        // The player is out: their things stay where they died, and the run goes on until enough are out.
        dropLoot(event, player, dropTag);
        PlayerResetter.clearItems(player);
        roster.joinRun(player.getUniqueId());
        roster.eliminate(player.getUniqueId());
        eliminations.addFirst(new Elimination(death, vanillaMessage));
        save();
        hud.update();
        logEvent(TimelineEvent.Type.ELIMINATED, player.getName(), death.message() != null ? death.message() : death.cause());
        if (settings.resetWhen().reached(roster.deadInRun(), roster.runSize())) {
            // This death is the fatal one; the death announcement covers it.
            reportRunEndingDeath(death, vanillaMessage);
            return;
        }
        announcer.chatAlways("eliminated",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.component("death_message", vanillaMessage != null ? vanillaMessage : RunReports.deathText(messages, death)),
                Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis())),
                Placeholder.unparsed("dead", String.valueOf(roster.deadInRun())),
                Placeholder.unparsed("total", String.valueOf(roster.runSize())),
                Placeholder.unparsed("needed", String.valueOf(settings.resetWhen().threshold(roster.runSize()))));
    }

    /** Drops what the cancelled death would have dropped (items and experience) at the death spot. */
    private void dropLoot(PlayerDeathEvent event, Player player, String tag) {
        Location where = player.getLocation();
        World world = where.getWorld();
        List<ItemStack> items = event.getKeepInventory()
                ? Arrays.asList(player.getInventory().getContents())
                : event.getDrops();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(where, item, drop -> drop.getPersistentDataContainer().set(dropKey, PersistentDataType.STRING, tag));
            }
        }
        int experience = event.getKeepLevel() ? player.calculateTotalExperiencePoints() : event.getDroppedExp();
        if (experience > 0) {
            world.spawn(where, ExperienceOrb.class, orb -> {
                orb.setExperience(experience);
                orb.getPersistentDataContainer().set(dropKey, PersistentDataType.STRING, tag);
            });
        }
    }

    /** Ends the run now if the reset rule is already met by the participants who are out. */
    private void enforceResetRule() {
        if (machine.phase() != RunPhase.RUNNING || machine.hasPendingDeath()) {
            return;
        }
        int dead = roster.deadInRun();
        if (dead == 0 || !settings.resetWhen().reached(dead, roster.runSize())) {
            return;
        }
        DeathRecord death = null;
        Component message = null;
        for (Elimination elimination : eliminations) {
            // The most recent elimination of someone who is still out.
            if (roster.isEliminated(elimination.death().playerId())) {
                death = elimination.death();
                message = elimination.message();
                break;
            }
        }
        if (death == null) {
            // Lost in a restart: use the player who was eliminated last.
            UUID last = null;
            for (UUID id : roster.eliminated()) {
                if (roster.isInRun(id)) {
                    last = id;
                }
            }
            if (last == null) {
                return;
            }
            death = new DeathRecord(last, roster.participants().get(last), "unknown", null, null, machine.worldName(), 0, 0, 0);
            message = null;
        }
        reportRunEndingDeath(death, message);
    }

    // ================================================================ reset rule

    /** /hcc resetwhen <first-death|N%>: changes the rule and saves it to config.yml. */
    public void setResetWhen(CommandSender sender, String raw) {
        Optional<ResetRule> rule = ResetRule.parse(raw);
        if (rule.isEmpty()) {
            sender.sendMessage(messages.chat("reset-when-invalid", Placeholder.unparsed("value", raw)));
            return;
        }
        // Pick up edits made to config.yml since the last reload before writing it back.
        if (!reloadConfigFile()) {
            sender.sendMessage(messages.chat("config-unreadable"));
            return;
        }
        plugin.getConfig().set("reset-when", rule.get().configValue());
        plugin.saveConfig();
        reload();
        announcer.chatAlways("reset-when-changed",
                Placeholder.unparsed("player", sender.getName()),
                Placeholder.component("rule", ruleText(settings.resetWhen())));
    }

    private Component ruleText(ResetRule rule) {
        return messages.plain(rule.firstDeath() ? "reset-rule-first-death" : "reset-rule-percent",
                Placeholder.unparsed("percent", String.valueOf(rule.percent())));
    }

    // ================================================================= seed list

    /** The list as it was at the last reload. */
    private SeedList seedList() {
        return new SeedList(settings.seedMode(), settings.seeds(), seedPosition, seedPicked);
    }

    /** False if config.yml has a YAML error: Bukkit would load it as empty, and that must not be saved. */
    private boolean configReadable() {
        return Settings.isReadable(plugin.getDataPath().resolve("config.yml"), logger);
    }

    /**
     * Wraps the saved position and pick into the list of the last reload (the list may have been
     * edited by hand) and notes its size. Not while config.yml has an error: the list looks empty
     * then, and the position has to survive until the file is fixed.
     */
    private void keepSeedPositionInList() {
        if (configReadable()) {
            SeedList list = seedList();
            seedPosition = list.position();
            seedPicked = list.picked();
            seedCount = list.size();
        }
    }

    /**
     * Keeps the mode recorded on the pick of a reset in progress in step with config.yml, since the run that
     * starts goes by it. Called after every successful read of the file, never when it has an error.
     */
    private void followSeedMode(SeedList.Mode mode) {
        if (!pendingChoice.fromList() || pendingChoice.listMode() == mode) {
            return;
        }
        SeedChoice before = pendingChoice;
        pendingChoice = before.withListMode(mode);
        if (transition != null && transition.choice.equals(before)) {
            transition.choice = pendingChoice;
        }
        save();
    }

    /** Re-reads config.yml. False if it has a YAML error. */
    private boolean reloadConfigFile() {
        if (!configReadable()) {
            return false;
        }
        plugin.reloadConfig();
        return true;
    }

    /** The list as it is in config.yml right now, so hand edits count, with the saved position. Null if config.yml has an error. */
    private SeedList readSeedList() {
        if (!reloadConfigFile()) {
            return null;
        }
        FileConfiguration config = plugin.getConfig();
        SeedList list = new SeedList(Settings.readSeedMode(config, logger), Settings.readSeeds(config, logger), seedPosition, seedPicked);
        followSeedMode(list.mode());
        if (list.isEmpty() && (seedPosition != 0 || seedPicked != list.picked())) {
            // Emptied by hand: a list written later starts at its first entry, and a pick is dropped.
            seedPosition = 0;
            seedPicked = list.picked();
            save();
        }
        list = removeUnconsumed(list);
        seedCount = list.size();
        return list;
    }

    /**
     * Uses up the entry of a run that started while config.yml had an error, with the pick it had and in the
     * list's mode as it is now: the admin may have changed it since. This is the first read since, so it
     * happens before the next pick.
     */
    private SeedList removeUnconsumed(SeedList list) {
        SeedList.Unconsumed pending = unconsumed;
        if (pending == null) {
            return list;
        }
        unconsumed = null;
        SeedList.Used used = list.consume(pending);
        saveSeedList(list, used.list(), false);
        if (used.exhausted()) {
            announceSeedsUsedUp();
        }
        return used.list();
    }

    /**
     * The entry the next run takes. While config.yml has an error the last reload is used, but not in
     * once mode: the entry could not be removed afterwards, so every run would take it again.
     */
    private Optional<SeedChoice> nextListSeed() {
        SeedList list = readSeedList();
        if (list == null) {
            list = seedList();
            if (list.mode() == SeedList.Mode.ONCE) {
                if (!list.isEmpty()) {
                    logger.warning("config.yml has an error, so this run gets a random seed instead of one from the seed list");
                }
                return Optional.empty();
            }
        }
        // Remember which entry it is, and the mode it was taken in: the list may be edited before the run starts.
        seedPicked = list.pick().picked();
        SeedList.Mode mode = list.mode();
        return list.next().map(entry -> SeedChoice.fromList(entry, mode));
    }

    /** The list for a command that changes it. Null, after telling the sender, if config.yml has an error. */
    private SeedList editableSeedList(CommandSender sender) {
        SeedList list = readSeedList();
        if (list == null) {
            sender.sendMessage(messages.chat("config-unreadable"));
        }
        return list;
    }

    /**
     * Saves a change made to a list from {@link #readSeedList()}: changed entries to config.yml, the
     * position to state.yml. The mode is only written when it is being set, so a hand-typed value stays.
     */
    private void saveSeedList(SeedList before, SeedList after, boolean writeMode) {
        seedPosition = after.position();
        seedPicked = after.picked();
        boolean seedsChanged = !after.seeds().equals(before.seeds());
        if (seedsChanged || writeMode) {
            FileConfiguration config = plugin.getConfig();
            if (seedsChanged) {
                config.set("seed-list.seeds", new ArrayList<>(after.seeds()));
            }
            if (writeMode) {
                config.set("seed-list.mode", after.mode().configValue());
            }
            plugin.saveConfig();
            reload();
        }
        save();
    }

    /** The run has started, so the seed it took from the list is used up. */
    private void useListSeed(SeedChoice choice) {
        if (!choice.fromList()) {
            return;
        }
        try {
            SeedList list = readSeedList();
            if (list == null) {
                // The settings are the defaults if the file was broken at startup, so go by the mode the entry
                // was picked in (any read of config.yml keeps it current). Cycle mode only moves the position, which
                // lives in state.yml and wraps when the list is read. A once-mode entry can't be removed now,
                // so it is remembered with its pick and used up when config.yml can be read again.
                SeedList.Mode mode = choice.listMode() != null ? choice.listMode() : settings.seedMode();
                SeedList.Deferred deferred = SeedList.defer(mode, choice.listEntry(), seedPicked, seedPosition);
                seedPosition = deferred.position();
                if (deferred.unconsumed() != null) {
                    unconsumed = deferred.unconsumed();
                    logger.warning("config.yml has an error, so seed " + choice.listEntry() + " of run #" + machine.runNumber()
                            + " stays in the seed list until the file is fixed");
                }
                seedPicked = -1;
                save();
                return;
            }
            SeedList.Used used = list.consume(choice.listEntry());
            saveSeedList(list, used.list(), false);
            if (used.exhausted()) {
                announceSeedsUsedUp();
            }
        } catch (RuntimeException e) {
            // The run is already going; a problem with the list must not stop it.
            logger.log(java.util.logging.Level.WARNING, "Could not update the seed list after starting run #" + machine.runNumber(), e);
        }
    }

    private void announceSeedsUsedUp() {
        Component message = messages.chat("seeds-used-up");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(HardcoreChallengePlugin.PERMISSION_ADMIN)) {
                player.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** Number of seeds in the list the last time it was read, for tab completion. Does not read config.yml. */
    public int seedCount() {
        return seedCount;
    }

    /** /hcc seeds */
    public void sendSeeds(CommandSender sender) {
        SeedList list = readSeedList();
        if (list == null) {
            sender.sendMessage(messages.chat("seeds-config-error"));
            return;
        }
        TagResolver mode = Placeholder.unparsed("mode", list.mode().configValue());
        if (list.isEmpty()) {
            sender.sendMessage(messages.chat("seeds-empty", mode));
            return;
        }
        sender.sendMessage(messages.chat("seeds-header", mode, Placeholder.unparsed("count", String.valueOf(list.size()))));
        for (int i = 0; i < list.size(); i++) {
            sender.sendMessage(messages.plain(i == list.nextIndex() ? "seeds-line-next" : "seeds-line",
                    Placeholder.unparsed("number", String.valueOf(i + 1)),
                    Placeholder.unparsed("seed", list.seeds().get(i))));
        }
    }

    /** /hcc seeds add <seed> */
    public void addSeed(CommandSender sender, String raw) {
        String entry = raw.trim();
        if (entry.isEmpty()) {
            sender.sendMessage(messages.chat("seeds-add-blank"));
            return;
        }
        SeedList list = editableSeedList(sender);
        if (list == null) {
            return;
        }
        saveSeedList(list, list.add(entry), false);
        sender.sendMessage(messages.chat("seeds-added",
                Placeholder.unparsed("seed", entry),
                Placeholder.unparsed("number", String.valueOf(list.size() + 1))));
    }

    /** /hcc seeds remove <number> (1-based, as shown by /hcc seeds) */
    public void removeSeed(CommandSender sender, int number) {
        SeedList list = editableSeedList(sender);
        if (list == null) {
            return;
        }
        if (number < 1 || number > list.size()) {
            sender.sendMessage(messages.chat("seeds-remove-invalid",
                    Placeholder.unparsed("number", String.valueOf(number)),
                    Placeholder.unparsed("count", String.valueOf(list.size()))));
            return;
        }
        String entry = list.seeds().get(number - 1);
        saveSeedList(list, list.remove(number - 1), false);
        sender.sendMessage(messages.chat("seeds-removed",
                Placeholder.unparsed("seed", entry),
                Placeholder.unparsed("number", String.valueOf(number))));
    }

    /** /hcc seeds clear */
    public void clearSeeds(CommandSender sender) {
        SeedList list = editableSeedList(sender);
        if (list == null) {
            return;
        }
        if (list.isEmpty()) {
            sender.sendMessage(messages.chat("seeds-empty", Placeholder.unparsed("mode", list.mode().configValue())));
            return;
        }
        saveSeedList(list, list.clear(), false);
        sender.sendMessage(messages.chat("seeds-cleared", Placeholder.unparsed("count", String.valueOf(list.size()))));
    }

    /** /hcc seeds mode <once|cycle> */
    public void setSeedMode(CommandSender sender, SeedList.Mode mode) {
        SeedList list = editableSeedList(sender);
        if (list == null) {
            return;
        }
        // A reset in progress took its entry in the old mode; the run that starts goes by the new one.
        followSeedMode(mode);
        saveSeedList(list, list.withMode(mode), true);
        sender.sendMessage(messages.chat("seeds-mode-" + mode.configValue()));
    }

    // ===================================================================== revive

    /** /hcc revive <player>: brings back a participant who is out (only with a percentage rule). */
    public void revive(CommandSender sender, String name) {
        if (machine.phase() != RunPhase.RUNNING || settings.resetWhen().firstDeath() || current == null) {
            sender.sendMessage(messages.chat("revive-unavailable"));
            return;
        }
        UUID target = null;
        for (UUID id : roster.eliminated()) {
            if (name.equalsIgnoreCase(roster.participants().get(id))) {
                target = id;
            }
        }
        if (target == null) {
            sender.sendMessage(messages.chat("revive-not-found", Placeholder.unparsed("player", name)));
            return;
        }
        Player player = Bukkit.getPlayer(target);
        if (player == null) {
            sender.sendMessage(messages.chat("revive-offline", Placeholder.unparsed("player", name)));
            return;
        }

        UUID id = target;
        roster.revive(id);
        runEnd.forgetDeath(id);
        eliminations.removeIf(elimination -> elimination.death().playerId().equals(id));
        roster.joinRun(id);
        protection.grant(id, settings.spawnProtectionSeconds());
        Location spawn = current.spawn();
        int run = machine.runNumber();
        player.teleportAsync(spawn).whenComplete((success, error) -> onMain(() -> {
            if (!player.isOnline() || machine.runNumber() != run || machine.phase() != RunPhase.RUNNING || !roster.isActive(id)) {
                return;
            }
            if (error != null || !Boolean.TRUE.equals(success)) {
                player.teleport(spawn);
            }
            PlayerResetter.revive(player);
            protection.grant(id, settings.spawnProtectionSeconds());
        }));
        logEvent(TimelineEvent.Type.REVIVED, player.getName(), null);
        save();
        hud.update();
        updateClock();
        announcer.chatAlways("revived",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("admin", sender.getName()));
    }

    // =================================================================== continue

    /**
     * /hcc continue: brings the run that just ended back to life instead of moving on to the next world.
     * Works during a reset and after /hcc stop, for a run that was not won.
     */
    public void continueRun(CommandSender sender) {
        int run = machine.runNumber();
        RunLog past = archive.get(run);
        boolean won = past != null && past.outcome() == Outcome.VICTORY;
        boolean resetting = machine.phase() == RunPhase.RESETTING;
        if (stateUnreadable || won || runEnd.startedOver() || machine.hasPendingDeath() || !machine.canContinue()
                || (current == null && !resetting)) {
            sender.sendMessage(messages.chat("continue-unavailable"));
            return;
        }
        RunWorlds runWorlds = current;
        if (runWorlds == null) {
            // Restarted during the reset: the old worlds are not loaded, but their folders may still be there.
            runWorlds = loadOldWorlds(run);
            if (runWorlds == null) {
                sender.sendMessage(messages.chat("continue-worlds-failed", Placeholder.unparsed("run", String.valueOf(run))));
                return;
            }
        }
        current = runWorlds;
        currentPaths = runWorlds.paths();
        // Worked out first: reopening the log drops the death the old data falls back on.
        Map<UUID, Restore> restores = planRestores(past);

        cancel(victoryTask);
        cancel(fireworksTask);
        cancelTransition();
        // The reset never happened, so the seed it took from the list is not used up.
        pendingChoice = SeedChoice.RANDOM;
        seedPicked = -1;
        machine.continueRun();
        store.removePendingDeletions(currentPaths);
        roster.clearEliminations();
        eliminations.clear();
        pendingDeathMessage = null;
        reopenLog(past);
        runEnd.clearRun();
        restores.forEach(runEnd::putPending);
        // Before any sweep: what a death dropped goes (now or when it loads) for everyone whose items go back.
        restores.values().stream()
                .filter(restore -> restore.hasItems() && restore.dropTag() != null)
                .forEach(restore -> runEnd.addClearedDrop(restore.dropTag()));
        save();
        hud.showAll();
        hud.update();
        updateClock();

        Location spawn = runWorlds.spawn();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!roster.isParticipant(id) && player.hasPermission(HardcoreChallengePlugin.PERMISSION_PLAY)) {
                // Joined while the challenge was stopped, so handleJoin did not add them.
                roster.add(id, player.getName());
                logEvent(TimelineEvent.Type.PARTICIPANT_ADDED, player.getName(), null);
            }
            Restore restore = restores.get(id);
            if (restore != null) {
                applyRestore(player, restore);
            } else if (roster.isParticipant(id) && roster.needsSync(id, run)) {
                // Joined during the countdown, so not in this run yet: in like a late joiner.
                moveIntoRun(player, spawn);
                sendWelcome(player);
            }
        }
        for (Map.Entry<UUID, Restore> entry : restores.entrySet()) {
            if (entry.getValue().hasItems() && Bukkit.getPlayer(entry.getKey()) == null) {
                // Offline: their items go back when they join, but the copy on the ground goes now.
                clearDeathDrops(run, entry.getValue());
            }
        }
        save();
        flushLive(true);
        enforceResetRule();
        logger.info("Run #" + run + " was continued by " + sender.getName() + " at " + TimeFormat.clock(machine.elapsedMillis()));
        announcer.chatAlways("continued",
                Placeholder.unparsed("player", sender.getName()),
                Placeholder.unparsed("run", String.valueOf(run)));
    }

    /** Loads the worlds of a run that is not loaded, if all its folders are still on disk. Null if not. */
    private RunWorlds loadOldWorlds(int run) {
        if (currentPaths.isEmpty() || !currentPaths.stream().allMatch(path -> Files.isDirectory(Paths.get(path)))) {
            return null;
        }
        return worlds.load(run, machine.seed(), currentPaths).orElse(null);
    }

    /**
     * What each participant of the run gets back. Dead ones (out, or whose death ended the run) go to where they
     * died with their inventory if it was recorded. The others go to where they were when the run ended
     * (online) or stay where they logged out if nothing was recorded (offline). Participants who were never in this
     * run are not in it.
     * Runs from before this was recorded fall back to the run log's death and to where people are now.
     */
    private Map<UUID, Restore> planRestores(RunLog past) {
        int run = machine.runNumber();
        DeathRecord ended = past != null ? past.death() : null;
        Map<UUID, Restore> restores = new LinkedHashMap<>();
        for (UUID id : roster.participants().keySet()) {
            if (roster.needsSync(id, run)) {
                continue;
            }
            Restore waiting = runEnd.pending(id);
            if (waiting != null) {
                // Already waiting from an earlier continue that they have not been put back by.
                restores.put(id, waiting);
                continue;
            }
            Player online = Bukkit.getPlayer(id);
            Restore death = runEnd.deaths().get(id);
            boolean endedIt = ended != null && ended.playerId().equals(id);
            if (death != null || endedIt || roster.isEliminated(id)) {
                Spot spot = death != null ? death.spot() : null;
                if (spot == null && endedIt && ended.world() != null) {
                    spot = new Spot(ended.world(), ended.x() + 0.5, ended.y(), ended.z() + 0.5, 0f, 0f);
                }
                if (spot == null && online != null && isRunWorld(online.getWorld())) {
                    spot = spotOf(online.getLocation());
                }
                restores.put(id, death != null
                        ? new Restore(spot, death.items(), death.experience(), true, death.dropTag())
                        : Restore.dead(spot, null, 0));
            } else {
                // Where they were when the run ended, even if they moved or logged out as a spectator since.
                Spot spot = runEnd.positions().get(id);
                if (spot == null && online != null && isRunWorld(online.getWorld())) {
                    spot = spotOf(online.getLocation());
                }
                restores.put(id, Restore.alive(spot));
            }
        }
        return restores;
    }

    /** Makes the finished log of the continued run live again, or starts one like recoverRunLogs if it is gone. */
    private void reopenLog(RunLog past) {
        int run = machine.runNumber();
        liveDetached = false;
        liveDirty = true;
        if (past != null) {
            past.reopen();
            live = past;
            return;
        }
        // Missing, or its file could not be read: the file on disk stays as it is, like after a restart.
        live = new RunLog(run, machine.seed(), machine.worldName(), machine.startedAt(), null, false);
        roster.runParticipants().forEach(live::addParticipant);
        liveDetached = archive.hasFile(run);
        if (liveDetached) {
            logger.warning("Run #" + run + " was continued but runs/run-" + run + ".yml could not be used."
                    + " Leaving it untouched; the rest of this run will not be saved.");
        }
    }

    /** Where the active participants who are playing are, so a run that ends can be continued from there. */
    private void recordPositions() {
        if (current == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Spectators are not playing (they are out, or the run is already over), so their spot does not count.
            if (roster.isActive(player.getUniqueId()) && player.getGameMode() != GameMode.SPECTATOR && isRunWorld(player.getWorld())) {
                runEnd.recordPosition(player.getUniqueId(), spotOf(player.getLocation()));
            }
        }
    }

    /**
     * Puts a participant back as the continued run has them: at their spot (moved to safe ground if it is not),
     * with their inventory if it was recorded, in survival, with spawn protection. Later if they are not online.
     */
    private void applyRestore(Player player, Restore restore) {
        UUID id = player.getUniqueId();
        int run = machine.runNumber();
        Location wanted = restoreLocation(player, restore.spot());
        protection.grant(id, settings.spawnProtectionSeconds());
        // Two chunks is at least 32 blocks each way, more than SafeSpot reads (its search radius plus the lava check).
        loadChunksAround(wanted, 2).whenComplete((chunks, error) -> onMain(() -> {
            if (!canRestore(player, run)) {
                return;
            }
            if (restore.hasItems() && restore.dropTag() != null) {
                // Before the items go back, so nothing is there twice.
                removeDeathDrops(chunks == null ? List.of() : chunks);
            }
            Location target = safeLocation(wanted);
            player.teleportAsync(target).whenComplete((success, teleportError) -> onMain(() -> {
                if (!canRestore(player, run)) {
                    return;
                }
                if (teleportError != null || !Boolean.TRUE.equals(success)) {
                    player.teleport(target);
                }
                if (restore.hasItems()) {
                    InventorySnapshot.apply(player, restore.items(), restore.experience(), logger);
                }
                if (restore.revive()) {
                    PlayerResetter.revive(player);
                } else {
                    player.setFallDistance(0f);
                    player.setGameMode(GameMode.SURVIVAL);
                }
                protection.grant(id, settings.spawnProtectionSeconds());
                runEnd.removePending(id);
                save();
            }));
        }));
    }

    private boolean canRestore(Player player, int run) {
        return player.isOnline() && machine.runNumber() == run && machine.phase() == RunPhase.RUNNING
                && roster.isActive(player.getUniqueId());
    }

    /** The saved spot if it is in a run world; otherwise where the player is, if that is in one, else the run spawn. */
    private Location restoreLocation(Player player, Spot spot) {
        if (spot != null) {
            Location saved = locationOf(spot);
            return saved != null ? saved : current.spawn();
        }
        return isRunWorld(player.getWorld()) ? player.getLocation() : current.spawn();
    }

    /** The spot as a location, or null if its world is not one of the run's. */
    private Location locationOf(Spot spot) {
        World world = Bukkit.getWorld(spot.world());
        if (world == null || !isRunWorld(world)) {
            return null;
        }
        return new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }

    /** The spot if a player can stand there safely, else the nearest safe place, else the run spawn. */
    private Location safeLocation(Location wanted) {
        try {
            Optional<SafeSpot.Block> found = SafeSpot.find(new BlockTerrain(wanted.getWorld()),
                    wanted.getBlockX(), wanted.getBlockY(), wanted.getBlockZ());
            if (found.isPresent()) {
                SafeSpot.Block block = found.get();
                if (block.x() == wanted.getBlockX() && block.y() == wanted.getBlockY() && block.z() == wanted.getBlockZ()) {
                    return wanted;
                }
                return new Location(wanted.getWorld(), block.x() + 0.5, block.y(), block.z() + 0.5, wanted.getYaw(), wanted.getPitch());
            }
        } catch (RuntimeException e) {
            logger.log(java.util.logging.Level.WARNING, "Could not check " + wanted + " for a safe spot", e);
        }
        return current.spawn();
    }

    /** Loads the chunks around a place (a radius in chunks) and returns the ones that loaded. */
    private CompletableFuture<List<Chunk>> loadChunksAround(Location where, int radius) {
        World world = where.getWorld();
        int centerX = where.getBlockX() >> 4;
        int centerZ = where.getBlockZ() >> 4;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                loads.add(world.getChunkAtAsync(centerX + dx, centerZ + dz));
            }
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).handle((ignored, error) -> {
            List<Chunk> chunks = new ArrayList<>();
            for (CompletableFuture<Chunk> load : loads) {
                if (load.isDone() && !load.isCompletedExceptionally() && load.join() != null) {
                    chunks.add(load.join());
                }
            }
            return chunks;
        });
    }

    /** Removes what a player's death dropped, for a player who is offline now and gets their items back on joining. */
    private void clearDeathDrops(int run, Restore restore) {
        Location where = restore.spot() == null ? null : locationOf(restore.spot());
        if (where == null || restore.dropTag() == null) {
            return;
        }
        loadChunksAround(where, 2).whenComplete((chunks, error) -> onMain(() -> {
            if (machine.runNumber() == run && current != null) {
                removeDeathDrops(chunks == null ? List.of() : chunks);
            }
        }));
    }

    /**
     * Removes the items and experience that deaths dropped (marked when they were dropped) whose items went back to
     * their owners: in the chunks around the death spots and anywhere else in the run's loaded chunks. What a teammate
     * picked up is gone. Entities of chunks that are not loaded yet are handled when they load.
     */
    private void removeDeathDrops(List<Chunk> around) {
        if (runEnd.clearedDrops().isEmpty()) {
            return;
        }
        int removed = 0;
        for (Chunk chunk : around) {
            for (Entity entity : chunk.getEntities()) {
                removed += removeIfDrop(entity);
            }
        }
        if (current != null) {
            for (World world : current.all()) {
                for (Entity entity : world.getEntitiesByClasses(Item.class, ExperienceOrb.class)) {
                    removed += removeIfDrop(entity);
                }
            }
        }
        if (removed > 0) {
            logger.info("Removed " + removed + " item(s) and orb(s) dropped by deaths whose items were given back");
        }
    }

    /** Entities that just loaded in a run world: removes the death drops that were given back already. */
    public void removeLoadedDeathDrops(World world, List<Entity> entities) {
        if (runEnd.clearedDrops().isEmpty() || !isRunWorld(world)) {
            return;
        }
        int removed = 0;
        for (Entity entity : entities) {
            removed += removeIfDrop(entity);
        }
        if (removed > 0) {
            logger.info("Removed " + removed + " item(s) and orb(s) dropped by deaths whose items were given back (loaded later)");
        }
    }

    /** Whether two dropped items (or orbs) may merge: not if they belong to different deaths, or one is not from a death. */
    public boolean sameDeathDrop(Entity first, Entity second) {
        String a = first.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        String b = second.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        return Objects.equals(a, b);
    }

    /** Only ground items and orbs carrying a tag of a cleared death are touched, never anything a player has. */
    private int removeIfDrop(Entity entity) {
        // No isValid check: entities in a load event may not be marked valid yet.
        if (!(entity instanceof Item || entity instanceof ExperienceOrb)) {
            return 0;
        }
        String tag = entity.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        if (tag != null && runEnd.clearedDrops().contains(tag)) {
            entity.remove();
            return 1;
        }
        return 0;
    }

    private static Spot spotOf(Location location) {
        return new Spot(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** Names of participants who are out of the current run, for tab completion. */
    public List<String> eliminatedNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : roster.eliminated()) {
            String name = roster.participants().get(id);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
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

        Component message = pendingDeathMessage != null ? pendingDeathMessage : RunReports.deathText(messages, death);
        pendingDeathMessage = null;
        String time = TimeFormat.clock(machine.elapsedMillis());
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
                Placeholder.unparsed("run", String.valueOf(machine.runNumber()))};
        announcer.chatAlways("death", placeholders);
        announcer.title("death-title", "death-subtitle", Duration.ofSeconds(3), placeholders);
        announcer.sound("death");
        announceRecap(record);

        runTransition(reasonText("reason-death", Placeholder.unparsed("player", death.playerName())), SeedChoice.RANDOM);
    }

    /** Short chat recap of a finished run, shown as the countdown starts. Blank messages turn it off. */
    private void announceRecap(RunLog record) {
        if (record == null || messages.isBlank("recap-header")) {
            return;
        }
        reports.sendRecap(Bukkit.getServer(), record.runNumber(), RunRecap.of(record, machine.trackedBosses().size()));
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
        endLog(Outcome.VICTORY, null, null);
        save();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        // From the state machine: there is no log if the run's file could not be continued.
        String order = killOrder(machine.bossKills());
        String champions = String.join(", ", roster.runParticipants().values());
        TagResolver[] placeholders = {
                Placeholder.unparsed("run", String.valueOf(machine.runNumber())),
                Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis())),
                Placeholder.unparsed("order", order),
                Placeholder.unparsed("participants", champions)};
        announcer.title("victory-title", "victory-subtitle", Duration.ofSeconds(6), placeholders);
        announcer.chatAlways("victory", placeholders);
        announcer.sound("victory");
        hud.update();

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
            runTransition(reasonText("reason-victory", Placeholder.unparsed("run", String.valueOf(machine.runNumber()))), SeedChoice.RANDOM);
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

    // ============================================================ connections

    /** Puts a joining player where they belong. The proxy may drop them anywhere, so never assume spawn. */
    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        roster.updateName(id, player.getName());
        hud.show(player);

        RunPhase phase = machine.phase();
        if (phase == RunPhase.IDLE) {
            if (current != null && isRunWorld(player.getWorld())) {
                recordLogoutSpot(player);
                player.setGameMode(GameMode.SPECTATOR);
            }
            return;
        }

        if (!roster.isParticipant(id) && player.hasPermission(HardcoreChallengePlugin.PERMISSION_PLAY)) {
            // New player: they're in the challenge now, even if there is no world yet (the transition
            // moves everyone in). Without a sync record they're wiped and moved in below or by the transition.
            roster.add(id, player.getName());
            if (phase == RunPhase.RUNNING) {
                logEvent(TimelineEvent.Type.PARTICIPANT_ADDED, player.getName(), null);
            }
            save();
            hud.update();
        } else if (phase == RunPhase.RUNNING && roster.isParticipant(id)) {
            logEvent(TimelineEvent.Type.JOINED, player.getName(), null);
        }
        if (current == null) {
            player.setGameMode(GameMode.SPECTATOR);
            return;
        }
        Location spawn = current.spawn();
        updateClock();
        if (phase == RunPhase.RUNNING && roster.isActive(id)) {
            Restore pending = runEnd.pending(id);
            if (pending != null) {
                if (!roster.needsSync(id, machine.runNumber())) {
                    // The run was continued while they were away: they get back what they had.
                    applyRestore(player, pending);
                    return;
                }
                runEnd.removePending(id);
            }
            if (roster.needsSync(id, machine.runNumber())) {
                // They were away when the run changed (or are new): bring them into this one fresh.
                moveIntoRun(player, spawn);
                save();
                sendWelcome(player);
            } else {
                // Already synced, but an earlier move may never have finished (they quit or it failed),
                // which leaves them in spectator or outside the run worlds.
                CompletableFuture<?> arrival = isRunWorld(player.getWorld())
                        ? CompletableFuture.completedFuture(null)
                        : player.teleportAsync(spawn);
                arrival.whenComplete((ignored, error) -> onMain(() -> backToSurvival(player)));
            }
            return;
        }

        // Spectators: players without hardcorechallenge.play, players who are out of this run,
        // or anyone during a reset / victory.
        if (phase == RunPhase.RESETTING) {
            recordLogoutSpot(player);
        }
        player.setGameMode(GameMode.SPECTATOR);
        if (!isRunWorld(player.getWorld())) {
            player.teleportAsync(spawn);
        }
    }

    /**
     * A participant who was offline when the run ended is where they logged out. Noted before they fly around as a
     * spectator, so continuing the run puts them back there.
     */
    private void recordLogoutSpot(Player player) {
        UUID id = player.getUniqueId();
        if (current == null || !isRunWorld(player.getWorld()) || !roster.isActive(id) || roster.needsSync(id, machine.runNumber())
                || runEnd.positions().containsKey(id) || runEnd.deaths().containsKey(id)) {
            return;
        }
        Restore pending = runEnd.pending(id);
        if (pending != null) {
            // A restore waiting without a spot gets the logout spot, so a second continue puts them back there.
            if (pending.spot() == null) {
                runEnd.putPending(id, new Restore(spotOf(player.getLocation()), pending.items(), pending.experience(),
                        pending.revive(), pending.dropTag()));
                save();
            }
            return;
        }
        runEnd.recordPosition(id, spotOf(player.getLocation()));
        save();
    }

    /** Puts an active participant who is still spectating back in survival. */
    private void backToSurvival(Player player) {
        if (player.isOnline() && machine.phase() == RunPhase.RUNNING && roster.isActive(player.getUniqueId())
                && player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    /** The short message for someone who joined a run that is already under way. */
    private void sendWelcome(Player player) {
        if (messages.isBlank("late-join-welcome")) {
            return;
        }
        int total = machine.trackedBosses().size();
        int count = (int) machine.trackedBosses().stream().filter(machine::hasKilled).count();
        player.sendMessage(messages.chat("late-join-welcome",
                Placeholder.unparsed("run", String.valueOf(machine.runNumber())),
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("total", String.valueOf(total)),
                Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis()))));
    }

    // ============================================================ participants

    private Component participantNames() {
        List<Component> names = new ArrayList<>();
        // Between runs, show who will be moved into the next one.
        Map<UUID, String> shown = machine.phase() == RunPhase.RESETTING && roster.runSize() == 0
                ? roster.participants()
                : roster.runParticipants();
        for (Map.Entry<UUID, String> entry : shown.entrySet()) {
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
        if (phase == RunPhase.IDLE && machine.runNumber() == 0) {
            sender.sendMessage(messages.chat("status-none"));
        } else if (phase == RunPhase.IDLE) {
            sender.sendMessage(messages.chat("status-idle", Placeholder.unparsed("run", run)));
        } else {
            sender.sendMessage(messages.chat("status-header",
                    Placeholder.unparsed("run", run),
                    Placeholder.component("phase", messages.plain("phase-" + phase.name().toLowerCase(Locale.ROOT)))));
        }
        if (machine.runNumber() > 0) {
            sender.sendMessage(messages.plain("status-time",
                    Placeholder.unparsed("time", TimeFormat.clock(machine.elapsedMillis())),
                    Placeholder.unparsed("seed", String.valueOf(machine.seed())),
                    Placeholder.unparsed("world", String.valueOf(machine.worldName()))));
            ResetRule rule = settings.resetWhen();
            sender.sendMessage(messages.plain("status-rule",
                    Placeholder.component("rule", ruleText(rule)),
                    Placeholder.unparsed("dead", String.valueOf(roster.deadInRun())),
                    Placeholder.unparsed("total", String.valueOf(roster.runSize())),
                    Placeholder.unparsed("needed", String.valueOf(rule.threshold(roster.runSize())))));
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
                Placeholder.component("players", roster.isEmpty() || roster.runSize() == 0 && machine.phase() != RunPhase.RESETTING
                        ? messages.plain("none") : participantNames())));
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
        long now = System.currentTimeMillis();
        pruneDeletes(now);
        pendingDeletes.put(deleteKey(sender), new PendingDelete(runNumber, now + DELETE_CONFIRM_MILLIS));
        sender.sendMessage(messages.chat("run-delete-confirm", Placeholder.unparsed("run", String.valueOf(runNumber)))
                .append(Component.space())
                .append(messages.plain("run-delete-button")
                        .clickEvent(ClickEvent.runCommand("/hcc run " + runNumber + " delete confirm"))));
    }

    /** /hcc run <n> delete confirm */
    public void confirmDelete(CommandSender sender, int runNumber) {
        pruneDeletes(System.currentTimeMillis());
        String key = deleteKey(sender);
        PendingDelete pending = pendingDeletes.get(key);
        if (pending == null) {
            sender.sendMessage(messages.chat("run-delete-expired", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        if (pending.run() != runNumber) {
            // Leave the real request alone so its own confirm button still works.
            sender.sendMessage(messages.chat("run-delete-other",
                    Placeholder.unparsed("run", String.valueOf(runNumber)),
                    Placeholder.unparsed("pending", String.valueOf(pending.run()))));
            return;
        }
        pendingDeletes.remove(key);
        RunLog run = archive.get(runNumber);
        if (run == null || run.isLive() || !archive.delete(runNumber)) {
            sender.sendMessage(messages.chat("run-not-found", Placeholder.unparsed("run", String.valueOf(runNumber))));
            return;
        }
        sender.sendMessage(messages.chat("run-deleted", Placeholder.unparsed("run", String.valueOf(runNumber))));
    }

    /** Players are told apart by UUID; the console and RCON sessions by name. */
    private static String deleteKey(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
    }

    private void pruneDeletes(long now) {
        pendingDeletes.values().removeIf(pending -> pending.expiresAt() < now);
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

    /** Adds an entry to the live run's timeline. It reaches the disk with the next flush. */
    public void logEvent(TimelineEvent.Type type, String player, String detail) {
        if (live == null) {
            return;
        }
        live.event(new TimelineEvent(machine.elapsedMillis(), System.currentTimeMillis(), type, player, detail));
        liveDirty = true;
        if (type == TimelineEvent.Type.RUN_STARTED) {
            // Make the new run's file exist right away.
            flushLive(true);
        }
    }

    /**
     * Writes the live log if it changed and the last write was at least {@link #LIVE_FLUSH_MILLIS} ago,
     * or always when forced. Every write also records the run time reached, for crash recovery.
     */
    private void flushLive(boolean force) {
        if (live == null || liveDetached) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && (!liveDirty || now - lastLiveFlush < LIVE_FLUSH_MILLIS)) {
            return;
        }
        live.checkpoint(machine.elapsedMillis(), now);
        archive.save(live);
        liveDirty = false;
        lastLiveFlush = now;
    }

    /** Finishes the live run's log and stores it. Returns the finished log, or null if there was none. */
    private RunLog endLog(Outcome outcome, DeathRecord death, String reason) {
        if (live == null) {
            return null;
        }
        // The phase has already moved on, so hand the log over explicitly.
        tracker.sample(live);
        long now = System.currentTimeMillis();
        // The death itself was logged when it happened (DEATH or ELIMINATED).
        live.event(new TimelineEvent(machine.elapsedMillis(), now, TimelineEvent.Type.RUN_ENDED, null,
                outcome.name().toLowerCase(Locale.ROOT)));
        live.finish(outcome, now, machine.elapsedMillis(), death, reason);
        RunLog finished = live;
        live = null;
        liveDirty = false;
        if (!liveDetached) {
            archive.save(finished);
        }
        liveDetached = false;
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
                // Whoever joined the run before the crash may not have reached the file yet.
                roster.runParticipants().forEach(live::addParticipant);
                liveDirty = true;
            } else {
                // A live log has no duration yet, so go by its last checkpoint and timeline.
                long duration = run.recoveredDurationMillis();
                long endedAt = run.recoveredEndedAt();
                run.event(new TimelineEvent(duration, endedAt, TimelineEvent.Type.RUN_ENDED, null, "stopped"));
                run.finish(Outcome.STOPPED, endedAt, duration, null, reasonText("reason-crashed"));
                archive.save(run);
            }
        }
        if (live == null && machine.phase() == RunPhase.RUNNING) {
            if (archive.hasFile(current)) {
                // state.yml and the run file disagree (crash between the two writes, or an unreadable file).
                // The file on disk wins; the rest of this run is only logged in memory (for the recap).
                logger.warning("Run #" + current + " is being resumed but runs/run-" + current + ".yml already exists"
                        + (archive.isBroken(current) ? " and could not be read" : " and is finished")
                        + ". Leaving it untouched; the rest of this run will not be saved.");
                live = new RunLog(current, machine.seed(), machine.worldName(), machine.startedAt(), null, false);
                roster.runParticipants().forEach(live::addParticipant);
                liveDetached = true;
            } else {
                live = new RunLog(current, machine.seed(), machine.worldName(), machine.startedAt(), null, false);
                roster.runParticipants().forEach(live::addParticipant);
            }
        }
        flushLive(true);
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

    /** A reason message as plain text, stored in the run log and shown as {@code <reason>}. */
    private String reasonText(String key, TagResolver... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(messages.plain(key, placeholders));
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
        SeedChoice choice;
        long deadlineMillis;
        int lastShownSecond = -1;
        long lastPreparingMillis;
        BukkitTask countdownTask;
        BukkitTask watchdog;
        CompletableFuture<RunWorlds> creation;
        boolean countdownDone;
        RunWorlds worlds;
        boolean completed;
        /** The state machine has moved on to the new run. */
        boolean started;
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
            ChallengeManager.cancel(watchdog);
        }

        /** Cancels the transition and the world creation behind it, which retires what it created. */
        void abort() {
            cancel();
            if (creation != null) {
                creation.cancel(true);
            }
        }
    }
}
