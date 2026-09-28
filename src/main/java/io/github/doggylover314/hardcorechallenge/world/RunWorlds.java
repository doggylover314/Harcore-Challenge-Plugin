package io.github.doggylover314.hardcorechallenge.world;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * The three dimensions of one run.
 */
public record RunWorlds(World overworld, World nether, World end) {

    public List<World> all() {
        return List.of(overworld, nether, end);
    }

    public List<String> paths() {
        return all().stream().map(world -> world.getWorldPath().toAbsolutePath().normalize().toString()).toList();
    }

    public boolean contains(World world) {
        return world != null && (world.equals(overworld) || world.equals(nether) || world.equals(end));
    }

    /** Centre of the overworld spawn block, safe to teleport to. */
    public Location spawn() {
        Location spawn = overworld.getSpawnLocation();
        return new Location(overworld, spawn.getBlockX() + 0.5, spawn.getY(), spawn.getBlockZ() + 0.5, spawn.getYaw(), 0f);
    }
}
