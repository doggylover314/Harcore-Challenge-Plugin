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
    void crashedRunKeepsTheLatestKnownDuration() {
        RunLog run = run();
        assertEquals(1000L, run.recoveredEndedAt());
        run.checkpoint(60_000L, 70_000L);
        run.event(new TimelineEvent(65_000L, 75_000L, TimelineEvent.Type.JOINED, "Steve", null));
        assertEquals(65_000L, run.recoveredDurationMillis());
        assertEquals(75_000L, run.recoveredEndedAt());
        run.event(new TimelineEvent(20_000L, 30_000L, TimelineEvent.Type.LEFT, "Steve", null));
        assertEquals(65_000L, run.recoveredDurationMillis());
        run.checkpoint(90_000L, 100_000L);
        assertEquals(90_000L, run.recoveredDurationMillis());
        assertEquals(100_000L, run.recoveredEndedAt());
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

    private static TimelineEvent event(long elapsed, TimelineEvent.Type type, String player) {
        return new TimelineEvent(elapsed, 1000L + elapsed, type, player, null);
    }

    @Test
    void reopeningAFinishedLogMakesItLiveAgain() {
        RunLog run = run();
        DeathRecord death = new DeathRecord(steve, "Steve", "lava", null, "Steve tried to swim in lava", "hcc_run_3", 1, 2, 3);
        run.event(event(0, TimelineEvent.Type.RUN_STARTED, null));
        run.event(event(500, TimelineEvent.Type.DEATH, "Steve"));
        run.event(event(500, TimelineEvent.Type.RUN_ENDED, null));
        run.finish(Outcome.DEATH, 2000L, 500L, death, "Steve died");

        assertTrue(run.reopen());

        assertTrue(run.isLive());
        assertNull(run.outcome());
        assertNull(run.death());
        assertNull(run.reason());
        assertEquals(0L, run.endedAt());
        assertEquals(0L, run.durationMillis());
        assertEquals(List.of(TimelineEvent.Type.RUN_STARTED, TimelineEvent.Type.DEATH),
                run.timeline().stream().map(TimelineEvent::type).toList());
    }

    @Test
    void reopeningKeepsTheRestOfTheLog() {
        RunLog run = run();
        run.bossKilled(Boss.WITHER, 400L, steve, "Steve");
        run.first("dim:the_nether");
        run.stats(steve, "Steve").addMobKill();
        run.event(event(400, TimelineEvent.Type.BOSS_KILLED, "Steve"));
        run.event(event(450, TimelineEvent.Type.ELIMINATED, "Alex"));
        run.event(event(500, TimelineEvent.Type.RUN_ENDED, null));
        run.finish(Outcome.STOPPED, 2000L, 500L, null, "stopped");

        run.reopen();

        assertEquals(2, run.timeline().size());
        assertEquals(1, run.bossKills().size());
        assertEquals("Steve", run.finalHit(Boss.WITHER));
        assertTrue(run.hasFirst("dim:the_nether"));
        assertEquals(1, run.stats().get(steve).mobsKilled());
        assertEquals(2, run.participants().size());
        assertEquals(1000L, run.startedAt());
    }

    @Test
    void reopeningAddsNoEventAndOnlyRemovesTheLastRunEnded() {
        RunLog run = run();
        run.event(event(100, TimelineEvent.Type.RUN_ENDED, null));
        run.event(event(200, TimelineEvent.Type.JOINED, "Alex"));
        run.event(event(300, TimelineEvent.Type.RUN_ENDED, null));
        run.finish(Outcome.STOPPED, 2000L, 300L, null, null);

        run.reopen();

        assertEquals(List.of(TimelineEvent.Type.RUN_ENDED, TimelineEvent.Type.JOINED),
                run.timeline().stream().map(TimelineEvent::type).toList());
    }

    @Test
    void aLiveLogIsLeftAloneByReopen() {
        RunLog run = run();
        run.event(event(100, TimelineEvent.Type.RUN_ENDED, null));
        assertFalse(run.reopen());
        assertEquals(1, run.timeline().size());
    }

    @Test
    void aReopenedLogCanEndAgain() {
        RunLog run = run();
        run.event(event(500, TimelineEvent.Type.RUN_ENDED, null));
        run.finish(Outcome.FORCED_RESET, 2000L, 500L, null, "forced");
        run.reopen();
        run.event(event(900, TimelineEvent.Type.RUN_ENDED, null));
        run.finish(Outcome.STOPPED, 3000L, 900L, null, "stopped");
        assertEquals(Outcome.STOPPED, run.outcome());
        assertEquals(900L, run.durationMillis());
        assertEquals(1, run.timeline().size());
    }
}
