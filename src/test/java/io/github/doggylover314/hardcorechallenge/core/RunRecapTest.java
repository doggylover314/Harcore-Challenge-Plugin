package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRecapTest {
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();

    private RunLog run() {
        RunLog run = new RunLog(5, 1L, "hcc_run_5", 0L, null, false);
        run.addParticipant(steve, "Steve");
        run.addParticipant(alex, "Alex");
        return run;
    }

    @Test
    void emptyRunIsInTheOverworldWithNoBosses() {
        RunLog run = run();
        run.finish(Outcome.FORCED_RESET, 10L, 4_000L, null, "test");
        RunRecap recap = RunRecap.of(run, 4);
        assertEquals(4_000L, recap.durationMillis());
        assertEquals(List.of(), recap.bosses());
        assertEquals(4, recap.totalBosses());
        assertEquals(RunRecap.Dimension.OVERWORLD, recap.furthest());
        assertNull(recap.topDamagePlayer());
        assertNull(recap.death());
    }

    @Test
    void furthestDimensionIsTheDeepestOne() {
        RunLog run = run();
        run.first("dim:the_nether");
        assertEquals(RunRecap.Dimension.NETHER, RunRecap.of(run, 1).furthest());
        run.first("dim:the_end");
        assertEquals(RunRecap.Dimension.END, RunRecap.of(run, 1).furthest());
    }

    @Test
    void topBossDamageAndKillsAndDeath() {
        RunLog run = run();
        run.stats(steve, "Steve").addDamage(300, true);
        run.stats(alex, "Alex").addDamage(100, true);
        run.bossKilled(Boss.WITHER, 5_000L, steve, "Steve");
        run.bossKilled(Boss.WARDEN, 6_000L, null, null);
        DeathRecord death = new DeathRecord(alex, "Alex", "lava", null, null, "w", 1, 2, 3);
        run.finish(Outcome.DEATH, 10L, 7_000L, death, null);

        RunRecap recap = RunRecap.of(run, 4);
        assertEquals(List.of(Boss.WITHER, Boss.WARDEN), recap.bosses());
        assertEquals("Steve", recap.topDamagePlayer());
        assertEquals(0.75, recap.topDamageShare(), 1e-9);
        assertSame(death, recap.death());
    }
}
