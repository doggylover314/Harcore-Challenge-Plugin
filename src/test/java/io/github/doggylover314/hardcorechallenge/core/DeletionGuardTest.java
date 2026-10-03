package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeletionGuardTest {
    private final Path container = Path.of("/srv/mc");
    private final Path mainWorld = container.resolve("world");
    private final List<Path> allowed = List.of(container);

    @Test
    void allowsAnUnusedRunWorld() {
        Path old = mainWorld.resolve("dimensions/minecraft/hcc_run_3");
        assertTrue(DeletionGuard.refusal(old, List.of(container, mainWorld), allowed).isEmpty());
    }

    @Test
    void refusesTheMainWorldAndAnythingNotARunWorld() {
        assertFalse(DeletionGuard.refusal(mainWorld, List.of(container, mainWorld), allowed).isEmpty());
        assertFalse(DeletionGuard.refusal(container.resolve("world_nether"), List.of(container), allowed).isEmpty());
        assertFalse(DeletionGuard.refusal(container, List.of(), allowed).isEmpty());
        assertFalse(DeletionGuard.refusal(null, List.of(), allowed).isEmpty());
    }

    @Test
    void refusesALoadedWorld() {
        Path current = container.resolve("hcc_run_4");
        assertFalse(DeletionGuard.refusal(current, List.of(current), allowed).isEmpty());
    }

    @Test
    void refusesAFolderThatContainsALoadedWorld() {
        Path parent = container.resolve("hcc_run_4");
        Path loadedInside = parent.resolve("hcc_run_5");
        assertFalse(DeletionGuard.refusal(parent, List.of(loadedInside), allowed).isEmpty());
    }

    @Test
    void normalisesPathsBeforeChecking() {
        Path sneaky = container.resolve("x/../hcc_run_4");
        assertFalse(DeletionGuard.refusal(sneaky, List.of(container.resolve("hcc_run_4")), allowed).isEmpty());
    }

    @Test
    void refusesARunWorldOutsideTheWorldContainer() {
        Path elsewhere = Path.of("/srv/other-server/world/dimensions/minecraft/hcc_run_3");
        assertFalse(DeletionGuard.refusal(elsewhere, List.of(), allowed).isEmpty());
        // A sibling whose name merely starts with the container's name is not inside it.
        assertFalse(DeletionGuard.refusal(Path.of("/srv/mc-copy/hcc_run_3"), List.of(), allowed).isEmpty());
    }

    @Test
    void refusesATraversalThatLeavesTheWorldContainer() {
        Path sneaky = container.resolve("world/../../other/hcc_run_3");
        assertFalse(DeletionGuard.refusal(sneaky, List.of(), allowed).isEmpty());
    }

    @Test
    void refusesEverythingWhenNoRootIsAllowed() {
        Path old = container.resolve("hcc_run_3");
        assertFalse(DeletionGuard.refusal(old, List.of(), List.of()).isEmpty());
    }

    @Test
    void isInsideIsStrict() {
        assertTrue(DeletionGuard.isInside(container.resolve("a/b"), allowed));
        assertFalse(DeletionGuard.isInside(container, allowed));
        assertFalse(DeletionGuard.isInside(Path.of("/srv"), allowed));
        assertFalse(DeletionGuard.isInside(null, allowed));
    }
}
