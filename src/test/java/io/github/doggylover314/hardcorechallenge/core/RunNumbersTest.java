package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunNumbersTest {

    @Test
    void nextIsOneAboveTheHighestSource() {
        assertEquals(1, RunNumbers.next(0, 0, 0));
        assertEquals(8, RunNumbers.next(0, 7, 3));
        assertEquals(10, RunNumbers.next(4, 2, 9));
        assertEquals(5, RunNumbers.next(4));
    }

    @Test
    void highestReadsRunWorldNamesOnly() {
        assertEquals(0, RunNumbers.highest(List.of()));
        assertEquals(12, RunNumbers.highest(List.of("hcc_run_3", "hcc_run_12_nether", "hcc_run_7_the_end", "world", "hcc_run_x")));
    }

    @Test
    void runFoldersFindsOnlyRunWorlds(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("hcc_run_2"));
        Files.createDirectories(dir.resolve("hcc_run_2_nether"));
        Files.createDirectories(dir.resolve("world"));
        Files.createFile(dir.resolve("hcc_run_9"));
        List<Path> found = RunNumbers.runFolders(dir);
        assertEquals(2, found.size());
        assertTrue(found.contains(dir.resolve("hcc_run_2")));
        assertTrue(RunNumbers.runFolders(dir.resolve("missing")).isEmpty());
    }
}
