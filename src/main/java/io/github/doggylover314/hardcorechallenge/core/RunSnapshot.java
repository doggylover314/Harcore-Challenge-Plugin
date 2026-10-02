package io.github.doggylover314.hardcorechallenge.core;

import java.util.List;
import java.util.Objects;

/**
 * Persistable view of the run state machine.
 */
public record RunSnapshot(
        RunPhase phase,
        int runNumber,
        String worldName,
        long seed,
        long startedAt,
        long elapsedMillis,
        List<BossKill> bossKills
) {
    public RunSnapshot {
        Objects.requireNonNull(phase, "phase");
        bossKills = List.copyOf(bossKills);
    }

    public static RunSnapshot initial() {
        return new RunSnapshot(RunPhase.IDLE, 0, null, 0L, 0L, 0L, List.of());
    }
}
