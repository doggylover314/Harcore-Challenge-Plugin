package io.github.doggylover314.hardcorechallenge.core;

import java.util.Objects;

/**
 * A place in a world, free of Bukkit types so it can be saved and tested.
 *
 * @param world world name
 */
public record Spot(String world, double x, double y, double z, float yaw, float pitch) {
    public Spot {
        Objects.requireNonNull(world, "world");
    }
}
