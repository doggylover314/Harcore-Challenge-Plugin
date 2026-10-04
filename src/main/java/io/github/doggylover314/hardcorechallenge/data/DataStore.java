package io.github.doggylover314.hardcorechallenge.data;

import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.BossKill;
import io.github.doggylover314.hardcorechallenge.core.Roster;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.RunSnapshot;
import io.github.doggylover314.hardcorechallenge.core.SeedChoice;
import io.github.doggylover314.hardcorechallenge.core.SeedList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads and writes the plugin's data files:
 * <ul>
 *   <li>{@code state.yml} - the current run and the participants</li>
 *   <li>{@code pending-deletions.yml} - world folders that could not be deleted yet</li>
 * </ul>
 * YAML is serialised on the calling (main) thread and written to disk on a single IO thread, so
 * writes happen in order and never block a tick.
 */
public final class DataStore {
    private final Path stateFile;
    private final Path pendingFile;
    private final Logger logger;
    private final ExecutorService io;

    private final Set<String> pendingDeletions = new LinkedHashSet<>();

    public DataStore(Path dataFolder, Logger logger) {
        this.stateFile = dataFolder.resolve("state.yml");
        this.pendingFile = dataFolder.resolve("pending-deletions.yml");
        this.logger = logger;
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HardcoreChallenge-IO");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ state

    /**
     * Everything needed to resume after a restart.
     *
     * @param pendingSeed  the replay / custom / seed list seed picked for the run being created while
     *                     RESETTING, or null (random seed or not resetting)
     * @param seedPosition where a cycle-mode seed list stands: index of the entry that is next
     * @param seedPicked   index of the seed list entry the run being created while RESETTING took, -1 if none
     *                     or {@link SeedList#DROPPED}
     * @param unconsumedSeed seed list entry of a run that started while config.yml had an error, so it could
     *                     not be used up yet, with the pick it had; null if none
     * @param unreadable   state.yml exists but could not be parsed; everything else is then an empty
     *                     initial state that must not be acted on or saved over the file
     */
    public record PersistedState(RunSnapshot run, List<String> worldPaths, Roster roster, SeedChoice pendingSeed,
                                 int seedPosition, int seedPicked, SeedList.Unconsumed unconsumedSeed, boolean unreadable) {
        public PersistedState(RunSnapshot run, List<String> worldPaths, Roster roster, SeedChoice pendingSeed,
                              int seedPosition, int seedPicked, SeedList.Unconsumed unconsumedSeed) {
            this(run, worldPaths, roster, pendingSeed, seedPosition, seedPicked, unconsumedSeed, false);
        }

        public PersistedState(RunSnapshot run, List<String> worldPaths, Roster roster, SeedChoice pendingSeed, int seedPosition) {
            this(run, worldPaths, roster, pendingSeed, seedPosition, -1, null, false);
        }

        public PersistedState(RunSnapshot run, List<String> worldPaths, Roster roster, SeedChoice pendingSeed) {
            this(run, worldPaths, roster, pendingSeed, 0, -1, null, false);
        }

        public PersistedState(RunSnapshot run, List<String> worldPaths, Roster roster) {
            this(run, worldPaths, roster, null, 0, -1, null, false);
        }
    }

    public PersistedState loadState() {
        Roster roster = new Roster();
        YamlConfiguration yaml = read(stateFile);
        if (yaml == null) {
            return new PersistedState(RunSnapshot.initial(), List.of(), roster, null, 0, -1, null, Files.isRegularFile(stateFile));
        }

        RunPhase phase;
        try {
            phase = RunPhase.valueOf(yaml.getString("phase", "IDLE"));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown phase in state.yml; treating the run as idle");
            phase = RunPhase.IDLE;
        }
        RunSnapshot run = new RunSnapshot(
                phase,
                yaml.getInt("run-number", 0),
                yaml.getString("world", null),
                yaml.getLong("seed", 0L),
                yaml.getLong("started-at", 0L),
                yaml.getLong("elapsed-millis", 0L),
                readKills(yaml.getMapList("boss-kills")));

        ConfigurationSection participants = yaml.getConfigurationSection("participants");
        if (participants != null) {
            for (String key : participants.getKeys(false)) {
                parseUuid(key).ifPresent(id -> roster.add(id, participants.getString(key, key)));
            }
        }
        for (String raw : yaml.getStringList("eliminated")) {
            parseUuid(raw).ifPresent(roster::eliminate);
        }
        ConfigurationSection synced = yaml.getConfigurationSection("synced-run");
        if (synced != null) {
            for (String key : synced.getKeys(false)) {
                parseUuid(key).ifPresent(id -> roster.markSynced(id, synced.getInt(key)));
            }
        }
        if (yaml.contains("in-run")) {
            for (String raw : yaml.getStringList("in-run")) {
                parseUuid(raw).ifPresent(roster::joinRun);
            }
        } else {
            // state.yml from before the in-run list existed: whoever was synced to this run is in it.
            roster.syncedRuns().forEach((id, syncedRun) -> {
                if (syncedRun == run.runNumber()) {
                    roster.joinRun(id);
                }
            });
        }
        return new PersistedState(run, yaml.getStringList("world-paths"), roster, readPendingSeed(yaml),
                Math.max(0, yaml.getInt("seed-position", 0)), readPick(yaml.getInt("seed-picked", -1)), readUnconsumed(yaml));
    }

    private static SeedList.Unconsumed readUnconsumed(YamlConfiguration yaml) {
        String entry = yaml.getString("unconsumed-list-entry");
        if (entry == null) {
            return null;
        }
        return new SeedList.Unconsumed(entry, readPick(yaml.getInt("unconsumed-list-picked", -1)));
    }

    /** A saved pick index: an index, -1 (none) or {@link SeedList#DROPPED}; anything else is none. */
    private static int readPick(int raw) {
        return raw == SeedList.DROPPED ? raw : Math.max(-1, raw);
    }

    /** A seed list mode as written in config.yml; null if missing (state.yml from before it was recorded). */
    private static SeedList.Mode readMode(String raw) {
        for (SeedList.Mode mode : SeedList.Mode.values()) {
            if (mode.configValue().equals(raw)) {
                return mode;
            }
        }
        return null;
    }

    private static SeedChoice readPendingSeed(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("pending-seed");
        if (section == null || !section.contains("seed")) {
            return null;
        }
        Integer replayOf = section.contains("replay-of") ? section.getInt("replay-of") : null;
        return new SeedChoice(section.getLong("seed"), replayOf, section.getBoolean("custom"), section.getString("list-entry"),
                readMode(section.getString("list-mode")));
    }

    public void saveState(PersistedState state) {
        writeAsync(stateFile, serializeState(state));
    }

    public void saveStateNow(PersistedState state) {
        writeNow(stateFile, serializeState(state));
    }

    private String serializeState(PersistedState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        RunSnapshot run = state.run();
        yaml.set("phase", run.phase().name());
        yaml.set("run-number", run.runNumber());
        yaml.set("world", run.worldName());
        yaml.set("seed", run.seed());
        yaml.set("started-at", run.startedAt());
        yaml.set("elapsed-millis", run.elapsedMillis());
        yaml.set("boss-kills", writeKills(run.bossKills()));
        yaml.set("world-paths", state.worldPaths());
        SeedChoice pending = state.pendingSeed();
        if (pending != null && pending.seed() != null) {
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("seed", pending.seed());
            if (pending.replayOf() != null) {
                seed.put("replay-of", pending.replayOf());
            }
            seed.put("custom", pending.custom());
            if (pending.fromList()) {
                seed.put("list-entry", pending.listEntry());
                if (pending.listMode() != null) {
                    seed.put("list-mode", pending.listMode().configValue());
                }
            }
            yaml.createSection("pending-seed", seed);
        }
        yaml.set("seed-position", state.seedPosition());
        if (state.seedPicked() != -1) {
            yaml.set("seed-picked", state.seedPicked());
        }
        SeedList.Unconsumed unconsumed = state.unconsumedSeed();
        if (unconsumed != null) {
            yaml.set("unconsumed-list-entry", unconsumed.entry());
            if (unconsumed.picked() != -1) {
                yaml.set("unconsumed-list-picked", unconsumed.picked());
            }
        }

        Roster roster = state.roster();
        Map<String, Object> participants = new LinkedHashMap<>();
        roster.participants().forEach((id, name) -> participants.put(id.toString(), name));
        yaml.createSection("participants", participants);
        yaml.set("eliminated", roster.eliminated().stream().map(UUID::toString).toList());
        yaml.set("in-run", roster.inRun().stream().map(UUID::toString).toList());
        Map<String, Object> synced = new LinkedHashMap<>();
        roster.syncedRuns().forEach((id, runNumber) -> synced.put(id.toString(), runNumber));
        yaml.createSection("synced-run", synced);
        return yaml.saveToString();
    }

    // ------------------------------------------------------- pending deletions

    public void loadPendingDeletions() {
        pendingDeletions.clear();
        YamlConfiguration yaml = read(pendingFile);
        if (yaml != null) {
            pendingDeletions.addAll(yaml.getStringList("paths"));
        }
    }

    public synchronized Set<String> pendingDeletions() {
        return Set.copyOf(pendingDeletions);
    }

    /** Thread-safe: deletion workers call this from the IO side. */
    public synchronized void addPendingDeletions(Collection<String> paths) {
        if (pendingDeletions.addAll(paths)) {
            writeAsync(pendingFile, serializePending());
        }
    }

    public synchronized void removePendingDeletions(Collection<String> paths) {
        if (pendingDeletions.removeAll(paths)) {
            writeAsync(pendingFile, serializePending());
        }
    }

    private synchronized String serializePending() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("paths", new ArrayList<>(pendingDeletions));
        return yaml.saveToString();
    }

    // -------------------------------------------------------------- lifecycle

    /** Runs a task on the IO thread, after any queued writes. */
    public void runOnIoThread(Runnable task) {
        io.execute(task);
    }

    /** Waits for queued writes to finish. Called on disable. */
    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Timed out waiting for data files to be written");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- helpers

    private YamlConfiguration read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            logger.log(Level.SEVERE, "Could not read " + file + "; keeping a copy as " + file.getFileName() + ".broken", e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Best effort only.
            }
            return null;
        }
    }

    /** Writes a file on the IO thread (atomically, via a temp file). */
    public void writeAsync(Path file, String content) {
        try {
            io.execute(() -> writeNow(file, content));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Shutting down: write synchronously so nothing is lost.
            writeNow(file, content);
        }
    }

    public void deleteAsync(Path file) {
        try {
            io.execute(() -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not delete " + file, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.warning("Shutting down; could not delete " + file);
        }
    }

    public void writeNow(Path file, String content) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            // A unique temp file, so the IO thread and a shutdown write to the same path can't collide.
            temp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not write " + file, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        }
    }

    private static java.util.Optional<UUID> parseUuid(String raw) {
        try {
            return java.util.Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private static List<Map<String, Object>> writeKills(List<BossKill> kills) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BossKill kill : kills) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("boss", kill.boss().id());
            map.put("elapsed-millis", kill.elapsedMillis());
            list.add(map);
        }
        return list;
    }

    private static List<BossKill> readKills(List<? extends Map<?, ?>> raw) {
        List<BossKill> kills = new ArrayList<>();
        if (raw == null) {
            return kills;
        }
        for (Map<?, ?> map : raw) {
            Boss.fromId(String.valueOf(map.get("boss")))
                    .ifPresent(boss -> kills.add(new BossKill(boss, asLong(map.get("elapsed-millis")))));
        }
        return kills;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
