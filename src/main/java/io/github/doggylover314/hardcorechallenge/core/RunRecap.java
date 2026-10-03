package io.github.doggylover314.hardcorechallenge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The facts shown in the short chat recap when a run ends.
 *
 * @param durationMillis  how long the run lasted
 * @param bosses          bosses killed, in kill order
 * @param totalBosses     bosses the run was scored against
 * @param furthest        the furthest dimension anyone reached
 * @param topDamagePlayer player who dealt the most boss damage, or {@code null} if nobody damaged a boss
 * @param topDamageShare  that player's share of all boss damage, 0 to 1
 * @param death           the fatal death, or {@code null}
 */
public record RunRecap(
        long durationMillis,
        List<Boss> bosses,
        int totalBosses,
        Dimension furthest,
        String topDamagePlayer,
        double topDamageShare,
        DeathRecord death
) {
    public enum Dimension {
        OVERWORLD,
        NETHER,
        END
    }

    /**
     * @param totalBosses number of bosses the run was scored against
     */
    public static RunRecap of(RunLog run, int totalBosses) {
        List<Boss> bosses = new ArrayList<>();
        for (BossKill kill : run.bossKills()) {
            bosses.add(kill.boss());
        }

        Dimension furthest = Dimension.OVERWORLD;
        if (run.hasFirst("dim:the_end")) {
            furthest = Dimension.END;
        } else if (run.hasFirst("dim:the_nether")) {
            furthest = Dimension.NETHER;
        }

        String topPlayer = null;
        double topShare = 0;
        for (Map.Entry<UUID, Double> entry : run.bossDamageShare().entrySet()) {
            if (entry.getValue() > topShare) {
                PlayerStats stats = run.stats().get(entry.getKey());
                if (stats != null) {
                    topShare = entry.getValue();
                    topPlayer = stats.name();
                }
            }
        }
        return new RunRecap(run.durationMillis(), List.copyOf(bosses), totalBosses, furthest, topPlayer, topShare, run.death());
    }
}
