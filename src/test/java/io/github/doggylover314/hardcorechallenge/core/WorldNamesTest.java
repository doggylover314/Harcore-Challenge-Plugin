package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class WorldNamesTest {
    @Test
    void namesUseVanillaSuffixes() {
        assertEquals(List.of("hcc_run_7", "hcc_run_7_nether", "hcc_run_7_the_end"), WorldNames.all(7));
    }

    @Test
    void recognisesOnlyRunWorlds() {
        assertTrue(WorldNames.isRunWorld("hcc_run_1"));
        assertTrue(WorldNames.isRunWorld("hcc_run_12_nether"));
        assertTrue(WorldNames.isRunWorld("hcc_run_12_the_end"));
        assertFalse(WorldNames.isRunWorld("world"));
        assertFalse(WorldNames.isRunWorld("world_nether"));
        assertFalse(WorldNames.isRunWorld("hcc_run_"));
        assertFalse(WorldNames.isRunWorld("hcc_run_1_backup"));
        assertFalse(WorldNames.isRunWorld("../hcc_run_1"));
        assertFalse(WorldNames.isRunWorld(null));
    }

    @Test
    void parsesRunNumbers() {
        assertEquals(OptionalInt.of(12), WorldNames.runNumberOf("hcc_run_12_nether"));
        assertEquals(OptionalInt.empty(), WorldNames.runNumberOf("world"));
        assertEquals("hcc_run_3", WorldNames.baseOf("hcc_run_3_the_end"));
    }
}
