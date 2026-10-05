package io.github.doggylover314.hardcorechallenge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doggylover314.hardcorechallenge.core.Restore;
import io.github.doggylover314.hardcorechallenge.core.Roster;
import io.github.doggylover314.hardcorechallenge.core.RunEnd;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.RunSnapshot;
import io.github.doggylover314.hardcorechallenge.core.SeedChoice;
import io.github.doggylover314.hardcorechallenge.core.SeedList;
import io.github.doggylover314.hardcorechallenge.core.Spot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataStoreTest {
    private final Logger logger = Logger.getLogger("test");

    private static DataStore.PersistedState resetting(SeedChoice pending, int seedPosition) {
        RunSnapshot run = new RunSnapshot(RunPhase.RESETTING, 3, "hcc_run_3", 99L, 1000L, 5000L, List.of());
        return new DataStore.PersistedState(run, List.of("hcc_run_3"), new Roster(), pending, seedPosition);
    }

    private DataStore.PersistedState roundTrip(Path folder, DataStore.PersistedState state) {
        DataStore store = new DataStore(folder, logger);
        store.saveStateNow(state);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();
        return loaded;
    }

    @Test
    void aSeedListChoiceSurvivesARestart(@TempDir Path folder) {
        SeedChoice pending = SeedChoice.fromList("my text seed");
        DataStore.PersistedState loaded = roundTrip(folder, resetting(pending, 0));

        assertEquals(pending, loaded.pendingSeed());
        assertTrue(loaded.pendingSeed().fromList());
        assertEquals("my text seed", loaded.pendingSeed().listEntry());
        assertEquals("my text seed".hashCode(), loaded.pendingSeed().seed());
    }

    @Test
    void aNumericEntryStaysTextExactlyAsWritten(@TempDir Path folder) {
        for (String entry : new String[] {"12345", "007", "-5", " 42 ", "true", "1e3", "9223372036854775807"}) {
            DataStore.PersistedState loaded = roundTrip(folder, resetting(SeedChoice.fromList(entry), 0));
            assertEquals(entry, loaded.pendingSeed().listEntry(), entry);
        }
    }

    @Test
    void replayAndCustomChoicesAreNotFromTheList(@TempDir Path folder) {
        SeedChoice replay = new SeedChoice(77L, 4, false);
        assertEquals(replay, roundTrip(folder, resetting(replay, 0)).pendingSeed());
        assertFalse(roundTrip(folder, resetting(replay, 0)).pendingSeed().fromList());

        SeedChoice custom = new SeedChoice(-8L, null, true);
        assertEquals(custom, roundTrip(folder, resetting(custom, 0)).pendingSeed());
        assertFalse(roundTrip(folder, resetting(custom, 0)).pendingSeed().fromList());
    }

    @Test
    void aRandomChoiceIsNotSaved(@TempDir Path folder) {
        assertNull(roundTrip(folder, resetting(SeedChoice.RANDOM, 0)).pendingSeed());
        assertNull(roundTrip(folder, resetting(null, 0)).pendingSeed());
    }

    @Test
    void theCyclePositionSurvivesARestart(@TempDir Path folder) {
        assertEquals(0, roundTrip(folder, resetting(null, 0)).seedPosition());
        assertEquals(3, roundTrip(folder, resetting(null, 3)).seedPosition());
    }

    @Test
    void thePickedEntryAndTheUnusedEntrySurviveARestart(@TempDir Path folder) {
        SeedChoice pending = SeedChoice.fromList("a");
        RunSnapshot run = new RunSnapshot(RunPhase.RESETTING, 3, "hcc_run_3", 99L, 1000L, 5000L, List.of());
        DataStore.PersistedState saved = new DataStore.PersistedState(
                run, List.of("hcc_run_3"), new Roster(), pending, 2, 1, new SeedList.Unconsumed("007", 3));
        DataStore.PersistedState loaded = roundTrip(folder, saved);

        assertEquals(1, loaded.seedPicked());
        assertEquals(new SeedList.Unconsumed("007", 3), loaded.unconsumedSeed());
        assertEquals(2, loaded.seedPosition());
        assertEquals(pending, loaded.pendingSeed());
    }

    @Test
    void noPickedEntryAndNoUnusedEntryAreNotSaved(@TempDir Path folder) throws IOException {
        DataStore.PersistedState loaded = roundTrip(folder, resetting(null, 0));
        assertEquals(-1, loaded.seedPicked());
        assertNull(loaded.unconsumedSeed());
        String yaml = Files.readString(folder.resolve("state.yml"));
        assertFalse(yaml.contains("seed-picked"));
        assertFalse(yaml.contains("unconsumed-list-entry"));
        assertFalse(yaml.contains("unconsumed-list-picked"));
    }

    @Test
    void theModeAListPickWasTakenInSurvivesARestart(@TempDir Path folder) {
        for (SeedList.Mode mode : SeedList.Mode.values()) {
            SeedChoice pending = SeedChoice.fromList("007", mode);
            DataStore.PersistedState loaded = roundTrip(folder, resetting(pending, 0));
            assertEquals(pending, loaded.pendingSeed());
            assertEquals(mode, loaded.pendingSeed().listMode());
        }
    }

    @Test
    void aDroppedPickSurvivesARestart(@TempDir Path folder) {
        RunSnapshot run = new RunSnapshot(RunPhase.RESETTING, 3, "hcc_run_3", 99L, 1000L, 5000L, List.of());
        DataStore.PersistedState saved = new DataStore.PersistedState(
                run, List.of(), new Roster(), SeedChoice.fromList("a", SeedList.Mode.ONCE), 0, SeedList.DROPPED,
                new SeedList.Unconsumed("a", SeedList.DROPPED));
        DataStore.PersistedState loaded = roundTrip(folder, saved);
        assertEquals(SeedList.DROPPED, loaded.seedPicked());
        assertEquals(new SeedList.Unconsumed("a", SeedList.DROPPED), loaded.unconsumedSeed());
    }

    @Test
    void aPendingSeedFromBeforeTheModeWasRecordedLoads(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("state.yml"), """
                phase: IDLE
                pending-seed:
                  seed: 5
                  custom: false
                  list-entry: "5"
                unconsumed-list-entry: "007"
                """);
        DataStore store = new DataStore(folder, logger);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();

        assertEquals(new SeedList.Unconsumed("007", -1), loaded.unconsumedSeed());
        assertEquals(SeedChoice.fromList("5"), loaded.pendingSeed());
        assertNull(loaded.pendingSeed().listMode());
    }

    @Test
    void anUnknownModeInStateYmlCountsAsNotRecorded(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("state.yml"), """
                phase: IDLE
                pending-seed:
                  seed: 5
                  custom: false
                  list-entry: "5"
                  list-mode: sometimes
                """);
        DataStore store = new DataStore(folder, logger);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();
        assertEquals(SeedChoice.fromList("5"), loaded.pendingSeed());
    }

    @Test
    void stateFromBeforeTheSeedListLoads(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("state.yml"), """
                phase: RESETTING
                run-number: 6
                world: hcc_run_6
                seed: 5
                pending-seed:
                  seed: 1234
                  replay-of: 2
                  custom: false
                """);
        DataStore store = new DataStore(folder, logger);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();

        assertFalse(loaded.unreadable());
        assertEquals(RunPhase.RESETTING, loaded.run().phase());
        assertEquals(new SeedChoice(1234L, 2, false), loaded.pendingSeed());
        assertFalse(loaded.pendingSeed().fromList());
        assertEquals(0, loaded.seedPosition());
        assertEquals(-1, loaded.seedPicked());
        assertNull(loaded.unconsumedSeed());
    }

    @Test
    void aMissingOrNegativePositionCountsAsZero(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("state.yml"), "phase: IDLE\nseed-position: -4\nseed-picked: -7\n");
        DataStore store = new DataStore(folder, logger);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();
        assertEquals(0, loaded.seedPosition());
        assertEquals(-1, loaded.seedPicked());
    }

    @Test
    void anEmptyFolderLoadsAnInitialState(@TempDir Path folder) {
        DataStore store = new DataStore(folder, logger);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();
        assertEquals(RunPhase.IDLE, loaded.run().phase());
        assertNull(loaded.pendingSeed());
        assertEquals(0, loaded.seedPosition());
        assertEquals(-1, loaded.seedPicked());
        assertNull(loaded.unconsumedSeed());
        assertFalse(loaded.unreadable());
    }

    private DataStore.PersistedState withRunEnd(RunEnd end) {
        RunSnapshot run = new RunSnapshot(RunPhase.IDLE, 3, "hcc_run_3", 99L, 1000L, 5000L, List.of());
        return new DataStore.PersistedState(run, List.of("hcc_run_3"), new Roster(), null, 0, -1, null, false, end);
    }

    @Test
    void whatIsNeededToContinueARunSurvivesARestart(@TempDir Path folder) {
        UUID dead = UUID.randomUUID();
        UUID firstDeath = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        UUID bare = UUID.randomUUID();
        RunEnd end = new RunEnd();
        end.recordDeath(dead, Restore.dead(new Spot("hcc_run_3_nether", 10.5, 70.0, -3.25, 90.5f, -12.25f),
                Map.of(0, "AAEC", 36, "Zm9v", 40, "YmFy"), 1395, "3:" + dead + ":1700000000000"));
        end.recordDeath(firstDeath, Restore.dead(new Spot("hcc_run_3", 1, 2, 3, 0f, 0f), null, 0));
        end.recordDeath(bare, Restore.dead(null, Map.of(), 0));
        end.recordPosition(alive, new Spot("hcc_run_3_the_end", -100.5, 49.0, 7.0, 180f, 30f));
        end.putPending(offline, Restore.alive(null));
        end.putPending(dead, Restore.dead(new Spot("hcc_run_3", 5, 6, 7, 1f, 2f), Map.of(3, "Zm9v"), 7));

        RunEnd loaded = roundTrip(folder, withRunEnd(end)).runEnd();

        assertFalse(loaded.startedOver());
        assertEquals("3:" + dead + ":1700000000000", loaded.deaths().get(dead).dropTag());
        assertNull(loaded.deaths().get(firstDeath).dropTag());
        assertEquals(end.deaths(), loaded.deaths());
        assertEquals(end.positions(), loaded.positions());
        assertEquals(end.pending(), loaded.pending());
        assertTrue(loaded.deaths().get(dead).hasItems());
        assertEquals("Zm9v", loaded.deaths().get(dead).items().get(36));
        assertEquals(1395, loaded.deaths().get(dead).experience());
        assertFalse(loaded.deaths().get(firstDeath).hasItems(), "first-death: only the spot is kept");
        assertTrue(loaded.deaths().get(bare).hasItems(), "an empty inventory is not the same as none");
        assertTrue(loaded.deaths().get(bare).items().isEmpty());
        assertNull(loaded.deaths().get(bare).spot());
        assertFalse(loaded.pending().get(offline).revive());
        assertNull(loaded.pending().get(offline).spot());
    }

    @Test
    void clearedDropsSurviveARestart(@TempDir Path folder) {
        RunEnd end = new RunEnd();
        end.addClearedDrop("3:" + UUID.randomUUID() + ":1700000000000");
        end.addClearedDrop("3:" + UUID.randomUUID() + ":1700000000500");
        RunEnd loaded = roundTrip(folder, withRunEnd(end)).runEnd();
        assertEquals(end.clearedDrops(), loaded.clearedDrops());
        assertEquals(2, loaded.clearedDrops().size());
        assertFalse(loaded.isEmpty());
    }

    @Test
    void aRunEndWithoutClearedDropsLoadsWithNone(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("state.yml"), """
                phase: IDLE
                run-end:
                  started-over: true
                """);
        DataStore store = new DataStore(folder, logger);
        RunEnd end = store.loadState().runEnd();
        store.shutdown();
        assertTrue(end.startedOver());
        assertTrue(end.clearedDrops().isEmpty());
    }

    @Test
    void startingOverSurvivesARestart(@TempDir Path folder) {
        RunEnd end = new RunEnd();
        end.markStartedOver();
        RunEnd loaded = roundTrip(folder, withRunEnd(end)).runEnd();
        assertTrue(loaded.startedOver());
        assertFalse(loaded.isEmpty());
    }

    @Test
    void anEmptyRunEndIsNotSaved(@TempDir Path folder) throws IOException {
        DataStore.PersistedState loaded = roundTrip(folder, withRunEnd(new RunEnd()));
        assertTrue(loaded.runEnd().isEmpty());
        assertFalse(Files.readString(folder.resolve("state.yml")).contains("run-end"));
    }

    @Test
    void aStateFileFromBeforeRunEndLoadsWithNone(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("state.yml"), """
                phase: IDLE
                run-number: 4
                world: hcc_run_4
                eliminated: []
                """);
        DataStore store = new DataStore(folder, logger);
        DataStore.PersistedState loaded = store.loadState();
        store.shutdown();

        assertEquals(4, loaded.run().runNumber());
        assertNotNull(loaded.runEnd());
        assertTrue(loaded.runEnd().isEmpty());
    }

    @Test
    void brokenEntriesInTheRunEndAreSkipped(@TempDir Path folder) throws IOException {
        UUID good = UUID.randomUUID();
        Files.writeString(folder.resolve("state.yml"), """
                phase: IDLE
                run-end:
                  deaths:
                    not-a-uuid:
                      world: hcc_run_4
                    %s:
                      world: hcc_run_4
                      x: 1.5
                      y: 2.0
                      z: 3.5
                      items:
                        '0': Zm9v
                        bad: YmFy
                      experience: 12
                  positions:
                    %s:
                      x: 1.0
                """.formatted(good, UUID.randomUUID()));
        DataStore store = new DataStore(folder, logger);
        RunEnd end = store.loadState().runEnd();
        store.shutdown();

        assertEquals(1, end.deaths().size());
        Restore death = end.deaths().get(good);
        assertEquals(new Spot("hcc_run_4", 1.5, 2.0, 3.5, 0f, 0f), death.spot());
        assertEquals(Map.of(0, "Zm9v"), death.items());
        assertEquals(12, death.experience());
        assertTrue(death.revive());
        assertTrue(end.positions().isEmpty(), "a position without a world is dropped");
    }
}
