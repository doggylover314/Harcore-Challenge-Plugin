package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeletionGuardTest {
    private final Path container = Path.of("/srv/mc");
    private final Path mainWorld = container.resolve("world");

    @Test
    void allowsAnUnusedRunWorld() {
        Path old = mainWorld.resolve("dimensions/minecraft/hcc_run_3");
        assertTrue(DeletionGuard.refusal(old, List.of(container, mainWorld)).isEmpty());
    }

    @Test
    void refusesTheMainWorldAndAnythingNotARunWorld() {
        assertFalse(DeletionGuard.refusal(mainWorld, List.of(container, mainWorld)).isEmpty());
        assertFalse(DeletionGuard.refusal(container.resolve("world_nether"), List.of(container)).isEmpty());
        assertFalse(DeletionGuard.refusal(container, List.of()).isEmpty());
        assertFalse(DeletionGuard.refusal(null, List.of()).isEmpty());
    }

    @Test
    void refusesALoadedWorld() {
        Path current = container.resolve("hcc_run_4");
        assertFalse(DeletionGuard.refusal(current, List.of(current)).isEmpty());
    }

    @Test
    void refusesAFolderThatContainsALoadedWorld() {
        Path parent = container.resolve("hcc_run_4");
        Path loadedInside = parent.resolve("hcc_run_5");
        assertFalse(DeletionGuard.refusal(parent, List.of(loadedInside)).isEmpty());
    }

    @Test
    void normalisesPathsBeforeChecking() {
        Path sneaky = container.resolve("hcc_run_4/../world");
        assertFalse(DeletionGuard.refusal(sneaky, List.of(mainWorld)).isEmpty());
    }
}
