package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RunStateMachineTest {
    private final AtomicLong runClock = new AtomicLong();
    private final AtomicLong wallClock = new AtomicLong(1_700_000_000_000L);
    private RunStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = new RunStateMachine(runClock::get, wallClock::get);
        machine.configure(EnumSet.allOf(Boss.class), true, true);
    }

    private void startRun(int run) {
        assertTrue(machine.beginTransition());
        machine.beginRun(run, WorldNames.overworld(run), 1000L + run);
    }

    private void advance(long millis) {
        runClock.addAndGet(millis);
        wallClock.addAndGet(millis);
    }

    private static DeathRecord death(String name) {
        return new DeathRecord(UUID.randomUUID(), name, "lava", null, name + " tried to swim in lava", "hcc_run_1", 1, 2, 3);
    }

    @Test
    void startsIdle() {
        assertEquals(RunPhase.IDLE, machine.phase());
        assertEquals(0, machine.runNumber());
        assertFalse(machine.isActive());
    }

    @Test
    void startGoesThroughResettingIntoRunning() {
        assertTrue(machine.beginTransition());
        assertEquals(RunPhase.RESETTING, machine.phase());
        machine.beginRun(1, "hcc_run_1", 42L);
        assertEquals(RunPhase.RUNNING, machine.phase());
        assertEquals(1, machine.runNumber());
        assertEquals("hcc_run_1", machine.worldName());
        assertEquals(42L, machine.seed());
        assertEquals(wallClock.get(), machine.startedAt());
    }

    @Test
    void cannotBeginRunUnlessResetting() {
        assertThrows(IllegalStateException.class, () -> machine.beginRun(1, "hcc_run_1", 1L));
        startRun(1);
        assertThrows(IllegalStateException.class, () -> machine.beginRun(2, "hcc_run_2", 1L));
    }

    @Test
    void runNumbersMustIncrease() {
        startRun(3);
        machine.beginTransition();
        assertThrows(IllegalArgumentException.class, () -> machine.beginRun(3, "hcc_run_3", 1L));
        assertThrows(IllegalArgumentException.class, () -> machine.beginRun(2, "hcc_run_2", 1L));
    }

    @Test
    void secondTransitionWhileResettingIsRejected() {
        assertTrue(machine.beginTransition());
        assertFalse(machine.beginTransition());
    }

    @Test
    void newRunClearsBossProgressAndClock() {
        startRun(1);
        advance(5_000);
        machine.reportBossKill(Boss.WITHER, 10);
        machine.beginTransition();
        machine.beginRun(2, "hcc_run_2", 7L);
        assertTrue(machine.bossKills().isEmpty());
        assertFalse(machine.hasKilled(Boss.WITHER));
        assertEquals(0L, machine.elapsedMillis());
    }

    @Nested
    class Deaths {
        @Test
        void deathIsPendingUntilALaterTick() {
            startRun(1);
            assertEquals(RunStateMachine.DeathResult.PENDING, machine.reportDeath(death("Steve"), 100));
            assertEquals(RunPhase.RUNNING, machine.phase());
            assertTrue(machine.hasPendingDeath());

            assertTrue(machine.resolvePendingDeath(100).isEmpty(), "must not resolve in the same tick");
            Optional<DeathRecord> resolved = machine.resolvePendingDeath(101);
            assertTrue(resolved.isPresent());
            assertEquals("Steve", resolved.get().playerName());
            assertEquals(RunPhase.RESETTING, machine.phase());
        }

        @Test
        void firstDeathWinsWhenSeveralLandInOneTick() {
            startRun(1);
            machine.reportDeath(death("Steve"), 100);
            assertEquals(RunStateMachine.DeathResult.ALREADY_PENDING, machine.reportDeath(death("Alex"), 100));
            assertEquals("Steve", machine.resolvePendingDeath(101).orElseThrow().playerName());
        }

        @Test
        void deathsOutsideARunAreIgnored() {
            assertEquals(RunStateMachine.DeathResult.IGNORED, machine.reportDeath(death("Steve"), 1));
            machine.beginTransition();
            assertEquals(RunStateMachine.DeathResult.IGNORED, machine.reportDeath(death("Steve"), 1));
        }

        @Test
        void clockStopsWhenTheRunIsLost() {
            startRun(1);
            advance(60_000);
            machine.reportDeath(death("Steve"), 5);
            machine.resolvePendingDeath(6);
            advance(60_000);
            assertEquals(60_000L, machine.elapsedMillis());
        }
    }

    @Nested
    class Bosses {
        @Test
        void killsAreRecordedInOrderWithRunTime() {
            startRun(1);
            advance(1_000);
            assertEquals(RunStateMachine.BossResult.RECORDED, machine.reportBossKill(Boss.WITHER, 1));
            advance(2_000);
            assertEquals(RunStateMachine.BossResult.RECORDED, machine.reportBossKill(Boss.ELDER_GUARDIAN, 2));
            assertEquals(List.of(new BossKill(Boss.WITHER, 1_000), new BossKill(Boss.ELDER_GUARDIAN, 3_000)), machine.bossKills());
        }

        @Test
        void duplicateKillsAreIgnored() {
            startRun(1);
            machine.reportBossKill(Boss.WITHER, 1);
            assertEquals(RunStateMachine.BossResult.DUPLICATE, machine.reportBossKill(Boss.WITHER, 2));
            assertEquals(1, machine.bossKills().size());
        }

        @Test
        void untrackedBossesDoNotCount() {
            machine.configure(List.of(Boss.ENDER_DRAGON, Boss.WITHER), true, true);
            startRun(1);
            assertEquals(RunStateMachine.BossResult.NOT_TRACKED, machine.reportBossKill(Boss.WARDEN, 1));
            assertTrue(machine.bossKills().isEmpty());
        }

        @Test
        void killsOutsideARunAreIgnored() {
            assertEquals(RunStateMachine.BossResult.IGNORED, machine.reportBossKill(Boss.WITHER, 1));
        }

        @Test
        void killingTheLastBossWins() {
            startRun(1);
            machine.reportBossKill(Boss.ELDER_GUARDIAN, 1);
            machine.reportBossKill(Boss.WARDEN, 2);
            machine.reportBossKill(Boss.WITHER, 3);
            assertEquals(RunPhase.RUNNING, machine.phase());
            advance(10_000);
            assertEquals(RunStateMachine.BossResult.VICTORY, machine.reportBossKill(Boss.ENDER_DRAGON, 4));
            assertEquals(RunPhase.VICTORY, machine.phase());

            assertEquals(List.of(Boss.ELDER_GUARDIAN, Boss.WARDEN, Boss.WITHER, Boss.ENDER_DRAGON),
                    machine.bossKills().stream().map(BossKill::boss).toList());
        }

        @Test
        void anyBossWinsWhenAllAreNotRequired() {
            machine.configure(EnumSet.allOf(Boss.class), false, true);
            startRun(1);
            assertEquals(RunStateMachine.BossResult.VICTORY, machine.reportBossKill(Boss.WARDEN, 1));
        }

        @Test
        void victoryCanBeDisabled() {
            machine.configure(EnumSet.allOf(Boss.class), true, false);
            startRun(1);
            for (Boss boss : Boss.values()) {
                assertEquals(RunStateMachine.BossResult.RECORDED, machine.reportBossKill(boss, 1));
            }
            assertEquals(RunPhase.RUNNING, machine.phase());
        }

        @Test
        void emptyBossListNeverWins() {
            machine.configure(List.of(), true, true);
            startRun(1);
            assertEquals(RunStateMachine.BossResult.NOT_TRACKED, machine.reportBossKill(Boss.WITHER, 1));
            assertFalse(machine.reevaluateVictory());
        }

        @Test
        void shrinkingTheBossListCanWinOnReevaluation() {
            startRun(1);
            machine.reportBossKill(Boss.WITHER, 1);
            assertFalse(machine.reevaluateVictory());
            machine.configure(List.of(Boss.WITHER), true, true);
            assertTrue(machine.reevaluateVictory());
            assertEquals(RunPhase.VICTORY, machine.phase());
        }
    }

    @Nested
    class SameTick {
        @Test
        void victoryInTheSameTickAsADeathWins() {
            machine.configure(List.of(Boss.WITHER), true, true);
            startRun(1);
            machine.reportDeath(death("Steve"), 500);
            assertEquals(RunStateMachine.BossResult.VICTORY, machine.reportBossKill(Boss.WITHER, 500));
            assertEquals(RunPhase.VICTORY, machine.phase());
            assertFalse(machine.hasPendingDeath());
            assertTrue(machine.resolvePendingDeath(501).isEmpty());
            assertEquals(RunPhase.VICTORY, machine.phase());
        }

        @Test
        void deathAfterVictoryInTheSameTickIsIgnored() {
            machine.configure(List.of(Boss.WITHER), true, true);
            startRun(1);
            machine.reportBossKill(Boss.WITHER, 500);
            assertEquals(RunStateMachine.DeathResult.IGNORED, machine.reportDeath(death("Steve"), 500));
            assertEquals(RunPhase.VICTORY, machine.phase());
        }

        @Test
        void bossKillOnALaterTickDoesNotOverrideADeath() {
            machine.configure(List.of(Boss.WITHER), true, true);
            startRun(1);
            machine.reportDeath(death("Steve"), 500);
            assertEquals(RunStateMachine.BossResult.IGNORED, machine.reportBossKill(Boss.WITHER, 501));
            assertTrue(machine.resolvePendingDeath(501).isPresent());
            assertEquals(RunPhase.RESETTING, machine.phase());
        }

        @Test
        void nonFinalBossKillInTheSameTickDoesNotCancelTheDeath() {
            startRun(1);
            machine.reportDeath(death("Steve"), 500);
            assertEquals(RunStateMachine.BossResult.RECORDED, machine.reportBossKill(Boss.WITHER, 500));
            assertTrue(machine.resolvePendingDeath(501).isPresent());
        }
    }

    @Nested
    class StopAndVictoryActions {
        @Test
        void stopGoesIdleAndKeepsTheWorldName() {
            startRun(4);
            assertEquals(RunPhase.RUNNING, machine.stop());
            assertEquals(RunPhase.IDLE, machine.phase());
            assertEquals("hcc_run_4", machine.worldName());
            assertEquals(4, machine.runNumber());
        }

        @Test
        void stopDiscardsAPendingDeath() {
            startRun(1);
            machine.reportDeath(death("Steve"), 1);
            machine.stop();
            assertFalse(machine.hasPendingDeath());
            assertTrue(machine.resolvePendingDeath(2).isEmpty());
        }

        @Test
        void victoryCanTransitionToTheNextRun() {
            machine.configure(List.of(Boss.WARDEN), true, true);
            startRun(1);
            machine.reportBossKill(Boss.WARDEN, 1);
            assertTrue(machine.beginTransition());
            machine.beginRun(2, "hcc_run_2", 3L);
            assertEquals(RunPhase.RUNNING, machine.phase());
            assertFalse(machine.hasKilled(Boss.WARDEN));
        }

        @Test
        void startFromIdleAfterStopUsesTheNextNumber() {
            startRun(1);
            machine.stop();
            assertTrue(machine.beginTransition());
            machine.beginRun(machine.runNumber() + 1, "hcc_run_2", 9L);
            assertEquals(2, machine.runNumber());
        }
    }

    @Nested
    class Pausing {
        @Test
        void pausedClockDoesNotCount() {
            startRun(1);
            advance(10_000);
            machine.pauseClock();
            advance(60_000);
            assertFalse(machine.clockRunning());
            assertEquals(10_000L, machine.elapsedMillis());
            machine.resumeClock();
            advance(5_000);
            assertEquals(15_000L, machine.elapsedMillis());
        }

        @Test
        void bossKillTimesUseThePausedClock() {
            startRun(1);
            advance(1_000);
            machine.pauseClock();
            advance(100_000);
            machine.resumeClock();
            advance(1_000);
            machine.reportBossKill(Boss.WITHER, 1);
            assertEquals(2_000L, machine.bossKills().getFirst().elapsedMillis());
        }

        @Test
        void pauseIsIgnoredOutsideARun() {
            machine.pauseClock();
            machine.resumeClock();
            assertFalse(machine.clockRunning());
            startRun(1);
            machine.stop();
            machine.resumeClock();
            assertFalse(machine.clockRunning());
        }

        @Test
        void pausedRunCanStillBeLost() {
            startRun(1);
            machine.pauseClock();
            machine.reportDeath(death("Steve"), 1);
            assertTrue(machine.resolvePendingDeath(2).isPresent());
            assertEquals(RunPhase.RESETTING, machine.phase());
        }
    }

    @Nested
    class Persistence {
        @Test
        void snapshotRoundTripResumesTheRun() {
            startRun(2);
            advance(90_000);
            machine.reportBossKill(Boss.WITHER, 1);
            RunSnapshot snapshot = machine.snapshot();

            RunStateMachine restored = new RunStateMachine(runClock::get, wallClock::get);
            restored.configure(EnumSet.allOf(Boss.class), true, true);
            advance(3_600_000); // server was offline for an hour
            restored.restore(snapshot);

            assertEquals(RunPhase.RUNNING, restored.phase());
            assertEquals(2, restored.runNumber());
            assertEquals(1002L, restored.seed());
            assertTrue(restored.hasKilled(Boss.WITHER));
            assertEquals(90_000L, restored.elapsedMillis(), "downtime is not counted");
            advance(1_000);
            assertEquals(91_000L, restored.elapsedMillis(), "clock resumes");
        }

        @Test
        void restoredVictoryKeepsItsClockStopped() {
            machine.configure(List.of(Boss.WITHER), true, true);
            startRun(1);
            advance(5_000);
            machine.reportBossKill(Boss.WITHER, 1);
            RunSnapshot snapshot = machine.snapshot();

            RunStateMachine restored = new RunStateMachine(runClock::get, wallClock::get);
            restored.restore(snapshot);
            advance(5_000);
            assertEquals(RunPhase.VICTORY, restored.phase());
            assertEquals(5_000L, restored.elapsedMillis());
        }

        @Test
        void restoredResettingStateCanBeginTheNextRun() {
            startRun(5);
            machine.reportDeath(death("Steve"), 1);
            machine.resolvePendingDeath(2);
            RunSnapshot snapshot = machine.snapshot();

            RunStateMachine restored = new RunStateMachine(runClock::get, wallClock::get);
            restored.restore(snapshot);
            assertEquals(RunPhase.RESETTING, restored.phase());
            restored.beginRun(6, "hcc_run_6", 1L);
            assertEquals(RunPhase.RUNNING, restored.phase());
        }
    }
}
