package io.github.doggylover314.hardcorechallenge.core;

/** How the next run's seed is chosen: random, a past run's seed, or one an admin typed. */
public record SeedChoice(Long seed, Integer replayOf, boolean custom) {
    public static final SeedChoice RANDOM = new SeedChoice(null, null, false);
}
