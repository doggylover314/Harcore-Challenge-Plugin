package io.github.doggylover314.hardcorechallenge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doggylover314.hardcorechallenge.core.RunLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunArchiveTest {
    private final Logger logger = Logger.getLogger("test");

    @Test
    void unreadableFilesAreCopiedAndNeverOverwritten(@TempDir Path folder) throws IOException {
        Path runs = Files.createDirectories(folder.resolve("runs"));
        Path broken = runs.resolve("run-4.yml");
        Files.writeString(broken, "run: [unclosed");

        DataStore store = new DataStore(folder, logger);
        RunArchive archive = new RunArchive(folder, store, logger);
        archive.load();

        assertNull(archive.get(4));
        assertTrue(archive.isBroken(4));
        assertTrue(archive.hasFile(4));
        assertTrue(Files.exists(runs.resolve("run-4.yml.broken")));

        archive.saveNow(new RunLog(4, 1L, "hcc_run_4", 0L, null, false));
        assertEquals("run: [unclosed", Files.readString(broken));
        assertNull(archive.get(4));
        store.shutdown();
    }

    @Test
    void runsAreKeyedByFileNameAndMismatchesAreBroken(@TempDir Path folder) throws IOException {
        DataStore store = new DataStore(folder, logger);
        new RunArchive(folder, store, logger).saveNow(new RunLog(6, 1L, "hcc_run_6", 0L, null, false));
        Path runs = folder.resolve("runs");
        // A run-6 file copied to another name must not shadow or replace the real run 6.
        Files.copy(runs.resolve("run-6.yml"), runs.resolve("run-3.yml"));

        RunArchive archive = new RunArchive(folder, store, logger);
        archive.load();

        assertNotNull(archive.get(6));
        assertNull(archive.get(3));
        assertTrue(archive.isBroken(3));
        assertEquals(1, archive.all().size());
        store.shutdown();
    }

    @Test
    void highestRunNumberIncludesBrokenFiles(@TempDir Path folder) throws IOException {
        DataStore store = new DataStore(folder, logger);
        RunArchive empty = new RunArchive(folder, store, logger);
        empty.load();
        assertEquals(0, empty.highestRunNumber());

        empty.saveNow(new RunLog(2, 1L, "hcc_run_2", 0L, null, false));
        Files.writeString(folder.resolve("runs").resolve("run-7.yml"), "run: [unclosed");

        RunArchive archive = new RunArchive(folder, store, logger);
        archive.load();
        assertEquals(7, archive.highestRunNumber());
        store.shutdown();
    }

    @Test
    void unreadableStateIsReported(@TempDir Path folder) throws IOException {
        DataStore store = new DataStore(folder, logger);
        assertFalse(store.loadState().unreadable());

        Files.writeString(folder.resolve("state.yml"), "phase: [unclosed");
        assertTrue(store.loadState().unreadable());
        assertTrue(Files.exists(folder.resolve("state.yml.broken")));
        store.shutdown();
    }

    @Test
    void savedRunsReload(@TempDir Path folder) {
        DataStore store = new DataStore(folder, logger);
        RunArchive archive = new RunArchive(folder, store, logger);
        RunLog run = new RunLog(2, 99L, "hcc_run_2", 5L, null, false);
        run.checkpoint(12_000L, 34L);
        archive.saveNow(run);
        assertTrue(archive.hasFile(2));
        assertFalse(archive.hasFile(3));

        RunArchive reloaded = new RunArchive(folder, store, logger);
        reloaded.load();
        RunLog copy = reloaded.get(2);
        assertNotNull(copy);
        assertEquals(12_000L, copy.checkpointMillis());
        assertFalse(reloaded.isBroken(2));
        store.shutdown();
    }

    @Test
    void concurrentWritesToOneFileDoNotCollide(@TempDir Path folder) throws Exception {
        DataStore store = new DataStore(folder, logger);
        Path file = folder.resolve("x.yml");
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            String content = "writer: " + i + "\n";
            threads[i] = new Thread(() -> {
                for (int n = 0; n < 50; n++) {
                    store.writeNow(file, content);
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(Files.readString(file).startsWith("writer: "));
        try (var files = Files.list(folder)) {
            assertEquals(1, files.count(), "no temp files left behind");
        }
        store.shutdown();
    }
}
