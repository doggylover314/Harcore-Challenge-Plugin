package io.github.doggylover314.hardcorechallenge.core;

import java.util.Objects;

/**
 * A boss that fell during a run, credited to the run rather than a single player.
 *
 * @param boss          which boss
 * @param elapsedMillis run time when it died
 */
public record BossKill(Boss boss, long elapsedMillis) {
    public BossKill {
        Objects.requireNonNull(boss, "boss");
    }
}
