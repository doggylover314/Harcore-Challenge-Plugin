package io.github.doggylover314.hardcorechallenge.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.BiomeTagKeys;
import io.papermc.paper.registry.tag.TagKey;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.plugin.Plugin;

/**
 * Finds a dry, solid spawn point near the origin of a new overworld.
 *
 * <p>Vanilla picks a spawn point while the world is being created, generating chunks on the main
 * thread; on modest hardware that stalls the server for several seconds per reset. Worlds are
 * therefore created with a forced spawn, and this class looks for real ground afterwards:
 * <ol>
 *   <li>Like vanilla, it first samples biome noise (no chunks are generated) to find the land
 *       nearest the origin, skipping oceans and rivers.</li>
 *   <li>It then generates only those candidate chunks with
 *       {@link World#getChunkAtAsync(int, int, boolean)}, off the main thread, and picks the first
 *       one with dry, solid ground.</li>
 * </ol>
 */
final class SpawnFinder {
    private static final int MAX_ATTEMPTS = 40;
    private static final int BATCH_SIZE = 4;
    /** Biome sampling reaches 64 chunks (1024 blocks) from the origin; sampling is cheap noise math. */
    private static final int BIOME_SEARCH_RADIUS_CHUNKS = 64;
    private static final int MAX_LAND_CANDIDATES = 24;
    private static final int FALLBACK_CANDIDATES = 8;

    private final Plugin plugin;
    private long samplingMillis;

    SpawnFinder(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Completes on the main thread with a location to spawn players at. Completes exceptionally
     * (rather than never) if anything in the search throws, or if {@code cancelled} turns true or
     * the world is unloaded while it runs.
     */
    CompletableFuture<Location> find(World world, BooleanSupplier cancelled) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        try {
            long started = System.currentTimeMillis();
            List<int[]> candidates = candidates(world);
            samplingMillis = System.currentTimeMillis() - started;
            tryBatch(world, candidates, 0, started, result, cancelled);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Requests a batch of chunks at once so every chunk worker thread is used, then checks them
     * nearest first.
     */
    private void tryBatch(World world, List<int[]> candidates, int from, long started, CompletableFuture<Location> result,
            BooleanSupplier cancelled) {
        if (abandoned(world, result, cancelled)) {
            return;
        }
        int to = Math.min(Math.min(candidates.size(), MAX_ATTEMPTS), from + BATCH_SIZE);
        if (from >= to) {
            // Nothing dry nearby (e.g. deep ocean): stand on whatever is at the origin.
            onMain(result, () -> finish(world, surface(world, 8, 8), from, started, result));
            return;
        }
        List<CompletableFuture<?>> loads = new ArrayList<>();
        try {
            for (int i = from; i < to; i++) {
                int[] chunk = candidates.get(i);
                loads.add(world.getChunkAtAsync(chunk[0], chunk[1], true));
            }
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return;
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> onMain(result, () -> {
            if (abandoned(world, result, cancelled)) {
                return;
            }
            for (int i = from; i < to; i++) {
                int[] chunk = candidates.get(i);
                if (!world.isChunkLoaded(chunk[0], chunk[1])) {
                    continue;
                }
                Location spot = dryGround(world, (chunk[0] << 4) + 8, (chunk[1] << 4) + 8);
                if (spot != null) {
                    finish(world, spot, i + 1, started, result);
                    return;
                }
            }
            tryBatch(world, candidates, to, started, result, cancelled);
        }));
    }

    /** Stops the search (and fails the result) once nobody wants it or its world is gone. */
    private static boolean abandoned(World world, CompletableFuture<Location> result, BooleanSupplier cancelled) {
        if (result.isDone()) {
            return true;
        }
        if (cancelled.getAsBoolean() || Bukkit.getWorld(world.getUID()) == null) {
            result.completeExceptionally(new CancellationException("The spawn search for " + world.getName() + " was cancelled"));
            return true;
        }
        return false;
    }

    private void finish(World world, Location spot, int checked, long started, CompletableFuture<Location> result) {
        plugin.getLogger().info("Spawn for " + world.getName() + " at " + spot.getBlockX() + " " + spot.getBlockY() + " "
                + spot.getBlockZ() + " (biome sampling " + samplingMillis + " ms, checked " + checked + " chunk(s), "
                + (System.currentTimeMillis() - started) + " ms total)");
        result.complete(spot);
    }

    private static Location dryGround(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y < world.getSeaLevel() - 1 || y > world.getMaxHeight() - 4) {
            return null;
        }
        Block ground = world.getBlockAt(x, y, z);
        if (ground.isLiquid() || !ground.getType().isSolid() || Tag.LOGS.isTagged(ground.getType())) {
            return null;
        }
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        if (feet.isLiquid() || feet.getType().isSolid() || head.isLiquid() || head.getType().isSolid()) {
            return null;
        }
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    private static Location surface(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    /**
     * Chunk coordinates to try, nearest first. Chunks whose centre is a land biome come first; the
     * plain grid follows as a fallback for all-ocean surroundings.
     */
    private static List<int[]> candidates(World world) {
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> grid = new ArrayList<>();
        addGrid(grid, seen, BIOME_SEARCH_RADIUS_CHUNKS, 2);
        grid.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));

        Set<Biome> water = waterBiomes();
        BiomeProvider biomes = world.vanillaBiomeProvider();
        int y = world.getSeaLevel();
        List<int[]> land = new ArrayList<>();
        for (int[] chunk : grid) {
            Biome biome = biomes.getBiome(world, (chunk[0] << 4) + 8, y, (chunk[1] << 4) + 8);
            if (!water.contains(biome)) {
                land.add(chunk);
                if (land.size() >= MAX_LAND_CANDIDATES) {
                    break;
                }
            }
        }
        List<int[]> ordered = new ArrayList<>(land);
        for (int[] chunk : grid.subList(0, Math.min(grid.size(), FALLBACK_CANDIDATES))) {
            if (!land.contains(chunk)) {
                ordered.add(chunk);
            }
        }
        return ordered;
    }

    private static Set<Biome> waterBiomes() {
        Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        Set<Biome> water = new HashSet<>();
        for (TagKey<Biome> tag : List.of(BiomeTagKeys.IS_OCEAN, BiomeTagKeys.IS_DEEP_OCEAN, BiomeTagKeys.IS_RIVER)) {
            if (registry.hasTag(tag)) {
                water.addAll(registry.getTagValues(tag));
            }
        }
        return water;
    }

    private static void addGrid(List<int[]> list, Set<Long> seen, int radius, int step) {
        for (int cx = -radius; cx <= radius; cx += step) {
            for (int cz = -radius; cz <= radius; cz += step) {
                if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) {
                    list.add(new int[] {cx, cz});
                }
            }
        }
    }

    /** Runs the task on the main thread; if it throws, or cannot run, the search fails instead of hanging. */
    private void onMain(CompletableFuture<?> result, Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            guarded.run();
        } else if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, guarded);
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        } else {
            result.completeExceptionally(new IllegalStateException("The plugin was disabled during the spawn search"));
        }
    }
}
