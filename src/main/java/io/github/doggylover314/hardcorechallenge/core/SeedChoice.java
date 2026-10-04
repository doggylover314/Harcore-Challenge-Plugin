package io.github.doggylover314.hardcorechallenge.core;

/**
 * How the next run's seed is chosen: random, a past run's seed, one an admin typed, or the next one
 * from the seed list.
 *
 * @param listEntry the seed list entry as written in the list, or {@code null} if the seed is not from the list
 * @param listMode  the seed list's mode when the entry was picked, or {@code null} if the seed is not from the
 *                  list or the mode was not recorded
 */
public record SeedChoice(Long seed, Integer replayOf, boolean custom, String listEntry, SeedList.Mode listMode) {
    public static final SeedChoice RANDOM = new SeedChoice(null, null, false);

    public SeedChoice(Long seed, Integer replayOf, boolean custom) {
        this(seed, replayOf, custom, null, null);
    }

    public SeedChoice(Long seed, Integer replayOf, boolean custom, String listEntry) {
        this(seed, replayOf, custom, listEntry, null);
    }

    /** The seed for a seed list entry. */
    public static SeedChoice fromList(String entry) {
        return fromList(entry, null);
    }

    /** The seed for a seed list entry that was picked while the list was in this mode. */
    public static SeedChoice fromList(String entry, SeedList.Mode mode) {
        return new SeedChoice(Seeds.parse(entry), null, false, entry, mode);
    }

    /** The same choice, with the list's mode as it is now: the admin changed it after the entry was picked. */
    public SeedChoice withListMode(SeedList.Mode mode) {
        return new SeedChoice(seed, replayOf, custom, listEntry, mode);
    }

    public boolean fromList() {
        return listEntry != null;
    }
}
