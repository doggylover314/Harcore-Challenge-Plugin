package io.github.doggylover314.hardcorechallenge.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a participant gets back when a run that ended is continued.
 *
 * @param spot       where they go, or {@code null} for where they are (or the run spawn if that is outside the run)
 * @param items      inventory by slot as Base64 item bytes, or {@code null} to leave the inventory as it is
 * @param experience total experience points, given back together with the items
 * @param revive     full health and food (for players who were out), otherwise they keep what they had
 * @param dropTag    marks what this death dropped on the ground, so only that is removed when the items go back
 */
public record Restore(Spot spot, Map<Integer, String> items, int experience, boolean revive, String dropTag) {
    public Restore {
        items = items == null ? null : Collections.unmodifiableMap(new TreeMap<>(items));
    }

    /** Someone who was still playing: back to survival at the spot, nothing else changes. */
    public static Restore alive(Spot spot) {
        return new Restore(spot, null, 0, false, null);
    }

    /** Someone who was out: revived at the spot, with their inventory back if it was recorded. */
    public static Restore dead(Spot spot, Map<Integer, String> items, int experience) {
        return new Restore(spot, items, experience, true, null);
    }

    public static Restore dead(Spot spot, Map<Integer, String> items, int experience, String dropTag) {
        return new Restore(spot, items, experience, true, dropTag);
    }

    public boolean hasItems() {
        return items != null;
    }
}
