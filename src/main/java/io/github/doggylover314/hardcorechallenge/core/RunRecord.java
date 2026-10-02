package io.github.doggylover314.hardcorechallenge.core;

import java.util.List;
import java.util.Objects;

/**
 * A completed run, as stored in history.
 *
 * @param runNumber      run number
 * @param seed           world seed
 * @param worldName      overworld name
 * @param outcome        how it ended
 * @param startedAt      epoch millis the run began
 * @param endedAt        epoch millis the run ended
 * @param durationMillis in-game run time
 * @param bossKills      bosses killed, in the order they fell
 * @param death          the death that ended the run, or {@code null}
 * @param reason         free-form reason (forced resets / stops), or {@code null}
 */
public record RunRecord(
        int runNumber,
        long seed,
        String worldName,
        Outcome outcome,
        long startedAt,
        long endedAt,
        long durationMillis,
        List<BossKill> bossKills,
        DeathRecord death,
        String reason
) {
    public RunRecord {
        Objects.requireNonNull(outcome, "outcome");
        bossKills = List.copyOf(bossKills);
    }

    public boolean isWin() {
        return outcome == Outcome.VICTORY;
    }
}
