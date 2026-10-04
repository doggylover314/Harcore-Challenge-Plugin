package io.github.doggylover314.hardcorechallenge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doggylover314.hardcorechallenge.core.Roster;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.RunSnapshot;
import io.github.doggylover314.hardcorechallenge.core.SeedChoice;
import io.github.doggylover314.hardcorechallenge.core.SeedList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
