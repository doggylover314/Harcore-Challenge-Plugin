package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunLogTest {
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();

    private RunLog run() {
        RunLog run = new RunLog(3, 42L, "hcc_run_3", 1000L, null, false);
        run.addParticipant(steve, "Steve");
        run.addParticipant(alex, "Alex");
        return run;
    }

    @Test
    void liveUntilFinished() {
        RunLog run = run();
        assertTrue(run.isLive());
        run.finish(Outcome.VICTORY, 2000L, 900L, null, null);
        assertFalse(run.isLive());
        assertEquals(Outcome.VICTORY, run.outcome());
    }

    @Test
    void firstsOnlyCountOnce() {
        RunLog run = run();
        assertTrue(run.first("dim:the_nether"));
        assertFalse(run.first("dim:the_nether"));
        assertTrue(run.hasFirst("dim:the_nether"));
    }

    @Test
    void bossDamageShare() {
        RunLog run = run();
        run.stats(steve, "Steve").addDamage(300, true);
        run.stats(alex, "Alex").addDamage(100, true);
        run.stats(alex, "Alex").addDamage(500, false);
        Map<UUID, Double> share = run.bossDamageShare();
        assertEquals(0.75, share.get(steve), 1e-9);
        assertEquals(0.25, share.get(alex), 1e-9);
        assertEquals(600, run.stats().get(alex).damageDealt(), 1e-9);
    }

    @Test
    void noBossDamageMeansNoShare() {
        assertTrue(run().bossDamageShare().isEmpty());
    }

    @Test
    void finalHitsAreCreditedToThePlayer() {
        RunLog run = run();
        run.bossKilled(Boss.WITHER, 5000L, steve, "Steve");
        run.bossKilled(Boss.WARDEN, 6000L, null, null);
        assertEquals("Steve", run.finalHit(Boss.WITHER));
        assertNull(run.finalHit(Boss.WARDEN));
        assertEquals(1, run.stats().get(steve).bossKills());
        assertEquals(List.of(Boss.WITHER, Boss.WARDEN), run.bossKills().stream().map(BossKill::boss).toList());
    }

    @Test
    void involvesIsCaseInsensitive() {
        assertTrue(run().involves("steve"));
        assertFalse(run().involves("Bob"));
    }

    @Test
    void negativeStatsAreIgnored() {
        PlayerStats stats = new PlayerStats("Steve");
        stats.addDistance(-50);
        stats.addTimePlayed(-1);
        stats.addDamage(-3, true);
        assertEquals(0, stats.distanceCm());
        assertEquals(0, stats.timePlayedMillis());
        assertEquals(0, stats.damageDealt(), 1e-9);
    }
}
