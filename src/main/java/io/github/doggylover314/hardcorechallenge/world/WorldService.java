package io.github.doggylover314.hardcorechallenge.world;

import io.github.doggylover314.hardcorechallenge.core.DeletionGuard;
import io.github.doggylover314.hardcorechallenge.core.WorldNames;
import io.github.doggylover314.hardcorechallenge.data.DataStore;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Creates, loads, unloads and deletes run worlds.
 *
 * <p>World creation and unloading must happen on the main thread (the API requires it), so the
 * three dimensions are created on consecutive ticks to spread the cost. Everything that touches
 * files happens on a dedicated cleanup thread.</p>
 */
public final class WorldService {
    private static final int DELETE_ATTEMPTS = 3;
    private static final long DELETE_RETRY_MILLIS = 2_000L;
    /** Ticks to wait after unloading before touching the folder, so the server can release region files. */
    private static final long DELETE_DELAY_TICKS = 40L;

    private final Plugin plugin;
    private final DataStore store;
    private final Logger logger;
    private final Path archiveRoot;
    private final ExecutorService cleanup;
    private final Supplier<Collection<String>> currentRunPaths;
    private final SpawnFinder spawnFinder;

    /**
     * @param currentRunPaths folders of the current (or kept) run; these are never deleted, loaded or not
     */
    public WorldService(Plugin plugin, DataStore store, Supplier<Collection<String>> currentRunPaths) {
        this.plugin = plugin;
        this.store = store;
        this.currentRunPaths = currentRunPaths;
        this.spawnFinder = new SpawnFinder(plugin);
        this.logger = plugin.getLogger();
        this.archiveRoot = plugin.getDataPath().resolve("archive");
        this.cleanup = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HardcoreChallenge-Cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- create

    /**
     * Creates (or loads, if the folders already exist) the three worlds of a run, one per tick.
     * Must be called on the main thread; the future completes on the main thread.
     */
    public CompletableFuture<RunWorlds> create(int runNumber, long seed) {
        CompletableFuture<RunWorlds> future = new CompletableFuture<>();
        World[] created = new World[3];
        World.Environment[] environments = {World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END};
        List<String> names = WorldNames.all(runNumber);
        CompletableFuture<Location> overworldSpawn = new CompletableFuture<>();

        for (int i = 0; i < 3; i++) {
            final int index = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (future.isDone()) {
                    return;
                }
                try {
                    World world = createOne(names.get(index), environments[index], seed, true);
                    created[index] = world;
                    if (index == 0) {
                        // Look for ground while the other two dimensions are being created.
                        spawnFinder.find(world).whenComplete((spawn, error) -> {
                            if (error != null) {
                                overworldSpawn.completeExceptionally(error);
                            } else {
                                overworldSpawn.complete(spawn);
                            }
                        });
                    }
                    if (index == 2) {
                        overworldSpawn.whenComplete((spawn, error) -> {
                            if (error != null) {
                                future.completeExceptionally(error);
                                return;
                            }
                            created[0].setSpawnLocation(spawn);
                            future.complete(new RunWorlds(created[0], created[1], created[2]));
                        });
                    }
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            }, 1L + i);
        }
        return future;
    }

    /**
     * @param forceSpawn skip vanilla's spawn search, which generates chunks on the main thread; the
     *                   real spawn is found asynchronously afterwards. Ignored for worlds that exist.
     */
    private World createOne(String name, World.Environment environment, long seed, boolean forceSpawn) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            // minecraft:<name> keys keep the world name exactly equal to hcc_run_<n>[_nether|_the_end].
            WorldCreator creator = WorldCreator.ofKey(NamespacedKey.minecraft(name))
                    .environment(environment)
                    .seed(seed)
                    .hardcore(true);
            if (forceSpawn) {
                creator.forcedSpawnPosition(Position.block(8, 64, 8), 0f, 0f);
            }
            world = Bukkit.createWorld(creator);
        }
        if (world == null) {
            throw new IllegalStateException("The server refused to create world " + name);
        }
        world.setDifficulty(Difficulty.HARD);
        return world;
    }

    /**
     * Loads the worlds of a run that was in progress when the server stopped.
     */
    public Optional<RunWorlds> load(int runNumber, long seed, List<String> knownPaths) {
        for (String raw : knownPaths) {
            if (!Files.isDirectory(Paths.get(raw))) {
                logger.warning("World folder " + raw + " is missing; it will be regenerated from seed " + seed + ".");
            }
        }
        try {
            World overworld = createOne(WorldNames.overworld(runNumber), World.Environment.NORMAL, seed, false);
            World nether = createOne(WorldNames.nether(runNumber), World.Environment.NETHER, seed, false);
            World end = createOne(WorldNames.end(runNumber), World.Environment.THE_END, seed, false);
            return Optional.of(new RunWorlds(overworld, nether, end));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not load the worlds of run #" + runNumber, e);
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- retire

    /**
     * Unloads the worlds of a finished run and deletes (or archives) their folders off-thread.
     * Players still inside are moved to {@code evacuateTo} first. Never touches a world that is
     * still occupied, the server's main level, or anything that is not a run world.
     *
     * @param runNumber   run the worlds belong to
     * @param knownPaths  folder paths recorded when the run was created (used if not loaded)
     * @param evacuateTo  where to send anyone still inside
     * @param keepOld     how many archived runs to keep (0 = delete)
     */
    public void retire(int runNumber, List<String> knownPaths, Location evacuateTo, int keepOld) {
        retire(runNumber, knownPaths, evacuateTo, keepOld, 0);
    }

    private void retire(int runNumber, List<String> knownPaths, Location evacuateTo, int keepOld, int attempt) {
        List<String> paths = new ArrayList<>(knownPaths);
        List<World> loaded = new ArrayList<>();
        for (String name : WorldNames.all(runNumber)) {
            World world = Bukkit.getWorld(name);
            if (world != null) {
                loaded.add(world);
                String path = world.getWorldPath().toAbsolutePath().normalize().toString();
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
        }

        boolean occupied = false;
        for (World world : loaded) {
            for (Player player : world.getPlayers()) {
                occupied = true;
                if (evacuateTo != null && !evacuateTo.getWorld().equals(world)) {
                    player.teleportAsync(evacuateTo);
                }
            }
        }
        if (occupied && attempt < 5) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> retire(runNumber, knownPaths, evacuateTo, keepOld, attempt + 1), 20L);
            return;
        }

        List<String> deletable = new ArrayList<>();
        // Unload the End and Nether before the Overworld.
        for (int i = loaded.size() - 1; i >= 0; i--) {
            World world = loaded.get(i);
            String path = world.getWorldPath().toAbsolutePath().normalize().toString();
            if (world.equals(mainWorld())) {
                logger.severe("Refusing to unload the server's main world " + world.getName());
                paths.remove(path);
                continue;
            }
            if (Bukkit.unloadWorld(world, false)) {
                logger.info("Unloaded " + world.getName());
            } else {
                // Could not unload (someone is still inside). It will not be loaded again after a
                // restart, so the folder can be removed then.
                logger.warning("Could not unload " + world.getName() + "; its folder will be deleted on the next server start.");
                store.addPendingDeletions(List.of(path));
                paths.remove(path);
            }
        }
        deletable.addAll(paths);
        if (deletable.isEmpty()) {
            return;
        }

        // Queued now in case the server stops before the delayed cleanup below gets to run.
        store.addPendingDeletions(deletable);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<Path> protectedRoots = protectedRoots();
            cleanup.execute(() -> disposeFolders(runNumber, deletable, protectedRoots, keepOld));
        }, DELETE_DELAY_TICKS);
    }

    /**
     * Retries deletions that failed earlier. Call once on startup, after the current run's worlds
     * are loaded, so those are protected.
     */
    public void processPendingDeletions() {
        List<String> pending = new ArrayList<>(store.pendingDeletions());
        if (pending.isEmpty()) {
            return;
        }
        // Loaded worlds and the current run are protected by the guard; such entries stay queued.
        List<Path> protectedRoots = protectedRoots();
        logger.info("Retrying deletion of " + pending.size() + " old world folder(s)");
        cleanup.execute(() -> {
            List<String> done = new ArrayList<>();
            for (String raw : pending) {
                Path path = Paths.get(raw);
                Optional<String> refusal = DeletionGuard.refusal(path, protectedRoots);
                if (refusal.isPresent()) {
                    if (WorldNames.isRunWorld(String.valueOf(path.getFileName()))) {
                        logger.info("Keeping " + raw + " queued for later: " + refusal.get());
                    } else {
                        logger.warning("Not deleting " + raw + ": " + refusal.get() + ". Removed from the queue.");
                        done.add(raw);
                    }
                    continue;
                }
                if (deleteWithRetries(path)) {
                    done.add(raw);
                }
            }
            store.removePendingDeletions(done);
        });
    }

    /**
     * Whether a run number must not be used for a new run: one of its worlds is still loaded, or
     * its folders are queued for deletion (a deletion could otherwise remove the new world).
     */
    public boolean isRunNumberTaken(int runNumber) {
        List<String> names = WorldNames.all(runNumber);
        for (String name : names) {
            if (Bukkit.getWorld(name) != null) {
                return true;
            }
        }
        for (String raw : store.pendingDeletions()) {
            Path fileName = Paths.get(raw).getFileName();
            if (fileName != null && names.contains(fileName.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Paths that must never be deleted: every loaded world, the current run, the main level and the
     * world container. Must be called on the main thread.
     */
    private List<Path> protectedRoots() {
        List<Path> roots = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            roots.add(world.getWorldPath());
        }
        for (String raw : currentRunPaths.get()) {
            roots.add(Paths.get(raw));
        }
        roots.add(Bukkit.getWorldContainer().toPath());
        roots.add(Bukkit.getWorldContainer().toPath().resolve(mainWorld().getName()));
        roots.add(archiveRoot);
        return roots;
    }

    private static World mainWorld() {
        return Bukkit.getWorlds().getFirst();
    }

    // ------------------------------------------------------- cleanup thread

    private void disposeFolders(int runNumber, List<String> rawPaths, List<Path> protectedRoots, int keepOld) {
        List<String> eligible = new ArrayList<>();
        for (String raw : rawPaths) {
            Optional<String> refusal = DeletionGuard.refusal(Paths.get(raw), protectedRoots);
            if (refusal.isPresent()) {
                logger.warning("Not deleting " + raw + ": " + refusal.get());
            } else {
                eligible.add(raw);
            }
        }
        // Queue first, so a shutdown halfway through a deletion is retried on the next start.
        store.addPendingDeletions(eligible);

        List<String> done = new ArrayList<>();
        for (String raw : eligible) {
            Path path = Paths.get(raw);
            boolean ok = !Files.exists(path) || (keepOld > 0 ? archive(runNumber, path) : deleteWithRetries(path));
            if (ok) {
                done.add(raw);
            }
        }
        store.removePendingDeletions(done);
        if (keepOld > 0) {
            pruneArchive(keepOld);
        }
        if (done.size() < eligible.size()) {
            logger.warning((eligible.size() - done.size()) + " old world folder(s) could not be removed now; they will be retried on the next server start.");
        }
    }

    private boolean archive(int runNumber, Path source) {
        Path target = archiveRoot.resolve(WorldNames.overworld(runNumber)).resolve(source.getFileName());
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                deleteTree(target);
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveFailed) {
                // Different file system or a locked file: copy, then delete the original.
                copyTree(source, target);
                return deleteWithRetries(source);
            }
            logger.info("Archived " + source + " to " + target);
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not archive " + source + "; deleting it instead", e);
            return deleteWithRetries(source);
        }
    }

    private void pruneArchive(int keep) {
        if (!Files.isDirectory(archiveRoot)) {
            return;
        }
        try (Stream<Path> runs = Files.list(archiveRoot)) {
            List<Path> sorted = runs
                    .filter(Files::isDirectory)
                    .filter(path -> WorldNames.runNumberOf(path.getFileName().toString()).isPresent())
                    .sorted(Comparator.comparingInt((Path path) -> WorldNames.runNumberOf(path.getFileName().toString()).getAsInt()).reversed())
                    .toList();
            for (Path old : sorted.subList(Math.min(keep, sorted.size()), sorted.size())) {
                try {
                    deleteTree(old);
                    logger.info("Removed archived run " + old.getFileName());
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not remove archived run " + old, e);
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not list " + archiveRoot, e);
        }
    }

    private boolean deleteWithRetries(Path path) {
        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            try {
                deleteTree(path);
                logger.info("Deleted " + path);
                return true;
            } catch (IOException e) {
                if (attempt == DELETE_ATTEMPTS) {
                    logger.log(Level.WARNING, "Could not delete " + path + " after " + attempt + " attempts", e);
                    return false;
                }
                try {
                    Thread.sleep(DELETE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null && !(exc instanceof NoSuchFileException)) {
                    throw exc;
                }
                try {
                    Files.deleteIfExists(dir);
                } catch (DirectoryNotEmptyException e) {
                    // Something wrote into the folder while we were deleting; let the retry handle it.
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void shutdown() {
        cleanup.shutdown();
        try {
            if (!cleanup.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("Old world cleanup did not finish before shutdown; unfinished folders are retried on the next start.");
                cleanup.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
