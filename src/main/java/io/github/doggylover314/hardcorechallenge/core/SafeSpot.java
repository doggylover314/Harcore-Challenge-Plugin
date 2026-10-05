package io.github.doggylover314.hardcorechallenge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds a spot where a player who is put back into a world does not die straight away: not in lava, fire or
 * a wall, not in the void, and with ground (or water) just below. Free of Bukkit types so it can be tested.
 */
public final class SafeSpot {
    /** How far down to look for ground; a fall of up to this minus one block does no damage. */
    static final int GROUND_DEPTH = 3;
    /** How far up and down a column is searched. */
    static final int RANGE = 5;
    /** How far sideways to look. */
    static final int RADIUS = 6;
    /** How close lava or fire may be sideways (a square around the spot, diagonals included). */
    static final int LAVA_DISTANCE = 2;
    /** How far above the head lava is looked for, as it can pour down. */
    static final int LAVA_ABOVE = 4;

    private static final List<int[]> OFFSETS = offsets();

    private SafeSpot() {
    }

    public enum Cell {
        /** Nothing in the way, nothing harmful. */
        OPEN,
        /** Water: fine to be in and to land in. */
        WATER,
        /** Something to stand on that is not harmful. */
        SOLID,
        /** Lava, fire and soul fire: they spread or burn what is next to them, so being near them is not safe either. */
        LAVA,
        /** Other things that hurt whoever stands in or on them. */
        HAZARD
    }

    public interface Terrain {
        Cell cell(int x, int y, int z);

        /** Lowest block height of the world. */
        int minY();

        /** One above the highest block height of the world. */
        int maxY();
    }

    public record Block(int x, int y, int z) {
    }

    /** Whether a player standing with their feet in this block is safe. */
    public static boolean isSafe(Terrain terrain, int x, int y, int z) {
        if (y < terrain.minY() || y + 1 >= terrain.maxY()) {
            return false;
        }
        if (!free(terrain.cell(x, y, z)) || !free(terrain.cell(x, y + 1, z))) {
            return false;
        }
        for (int below = 1; below <= GROUND_DEPTH; below++) {
            if (y - below < terrain.minY()) {
                return false;
            }
            switch (terrain.cell(x, y - below, z)) {
                case SOLID, WATER -> {
                    return !lavaNear(terrain, x, y, z, y - below);
                }
                case LAVA, HAZARD -> {
                    return false;
                }
                case OPEN -> {
                }
            }
        }
        return false;
    }

    /**
     * Whether lava is close enough to the spot to flow into it or touch the player: around the ground (one below
     * the feet, or lower if the player lands from a short fall), the feet and the head.
     */
    private static boolean lavaNear(Terrain terrain, int x, int y, int z, int groundY) {
        int low = Math.max(Math.min(y - 1, groundY), terrain.minY());
        int high = Math.min(y + 2, terrain.maxY() - 1);
        for (int dx = -LAVA_DISTANCE; dx <= LAVA_DISTANCE; dx++) {
            for (int dz = -LAVA_DISTANCE; dz <= LAVA_DISTANCE; dz++) {
                for (int cy = low; cy <= high; cy++) {
                    if (terrain.cell(x + dx, cy, z + dz) == Cell.LAVA) {
                        return true;
                    }
                }
            }
        }
        // Straight above the head.
        int top = Math.min(y + 1 + LAVA_ABOVE, terrain.maxY() - 1);
        for (int cy = high + 1; cy <= top; cy++) {
            if (terrain.cell(x, cy, z) == Cell.LAVA) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block itself if it is safe, otherwise the nearest safe one: the same column down, then up, then the
     * columns around it, closest first. Empty if there is none close by.
     */
    public static Optional<Block> find(Terrain terrain, int x, int y, int z) {
        if (isSafe(terrain, x, y, z)) {
            return Optional.of(new Block(x, y, z));
        }
        for (int[] offset : OFFSETS) {
            int cx = x + offset[0];
            int cz = z + offset[1];
            // The own column was tried above; the others start at the same height.
            if ((offset[0] != 0 || offset[1] != 0) && isSafe(terrain, cx, y, cz)) {
                return Optional.of(new Block(cx, y, cz));
            }
            for (int step = 1; step <= RANGE; step++) {
                if (isSafe(terrain, cx, y - step, cz)) {
                    return Optional.of(new Block(cx, y - step, cz));
                }
            }
            for (int step = 1; step <= RANGE; step++) {
                if (isSafe(terrain, cx, y + step, cz)) {
                    return Optional.of(new Block(cx, y + step, cz));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean free(Cell cell) {
        return cell == Cell.OPEN || cell == Cell.WATER;
    }

    /** Column offsets, the own column first, then closest first. */
    private static List<int[]> offsets() {
        List<int[]> list = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                list.add(new int[] {dx, dz});
            }
        }
        list.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1])
                .thenComparingInt(o -> o[0]).thenComparingInt(o -> o[1]));
        return List.copyOf(list);
    }
}
