package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunEndTest {
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final Spot spot = new Spot("hcc_run_2", 1.5, 64, -2.5, 90f, 10f);

    @Test
    void startsEmpty() {
        RunEnd end = new RunEnd();
        assertTrue(end.isEmpty());
        assertNull(end.pending(steve));
    }

    @Test
    void aRevivedPlayersDeathNoLongerCounts() {
        RunEnd end = new RunEnd();
        end.recordDeath(steve, Restore.dead(spot, null, 0));
        end.recordDeath(alex, Restore.dead(spot, Map.of(0, "x"), 5));
        end.forgetDeath(steve);
        assertEquals(1, end.deaths().size());
        assertTrue(end.deaths().containsKey(alex));
    }

    @Test
    void aLaterDeathReplacesAnEarlierOne() {
        RunEnd end = new RunEnd();
        end.recordDeath(steve, Restore.dead(spot, null, 0));
        Spot later = new Spot("hcc_run_2", 9, 9, 9, 0f, 0f);
        end.recordDeath(steve, Restore.dead(later, null, 0));
        assertEquals(later, end.deaths().get(steve).spot());
    }

    @Test
    void clearRunKeepsWhatIsStillToBeGivenBack() {
        RunEnd end = new RunEnd();
        end.recordDeath(steve, Restore.dead(spot, null, 0));
        end.recordPosition(alex, spot);
        end.putPending(alex, Restore.alive(null));
        end.clearRun();
        assertTrue(end.deaths().isEmpty());
        assertTrue(end.positions().isEmpty());
        assertEquals(Restore.alive(null), end.pending(alex));
        assertFalse(end.isEmpty());
    }

    @Test
    void clearForgetsEverything() {
        RunEnd end = new RunEnd();
        end.recordDeath(steve, Restore.dead(spot, null, 0));
        end.recordPosition(alex, spot);
        end.putPending(alex, Restore.alive(null));
        end.clear();
        assertTrue(end.isEmpty());
    }

    @Test
    void aPendingRestoreIsRemovedOnceGivenBack() {
        RunEnd end = new RunEnd();
        end.putPending(steve, Restore.alive(spot));
        end.removePending(steve);
        assertNull(end.pending(steve));
        assertTrue(end.isEmpty());
    }

    @Test
    void startingOverIsRememberedUntilEverythingIsCleared() {
        RunEnd end = new RunEnd();
        end.markStartedOver();
        assertTrue(end.startedOver());
        assertFalse(end.isEmpty());
        end.clearRun();
        assertTrue(end.startedOver());
        end.clear();
        assertFalse(end.startedOver());
        assertTrue(end.isEmpty());
    }

    @Test
    void aDeathKeepsTheTagOfItsDrops() {
        assertEquals("2:x:1", Restore.dead(spot, Map.of(0, "a"), 1, "2:x:1").dropTag());
        assertNull(Restore.dead(spot, Map.of(0, "a"), 1).dropTag());
        assertNull(Restore.alive(spot).dropTag());
    }

    @Test
    void clearedDropsAreKeptOnceAndMakeTheRunEndNonEmpty() {
        RunEnd end = new RunEnd();
        end.addClearedDrop("2:a:1");
        end.addClearedDrop("2:a:1");
        end.addClearedDrop("2:b:2");
        assertEquals(List.of("2:a:1", "2:b:2"), List.copyOf(end.clearedDrops()));
        assertFalse(end.isEmpty());
    }

    @Test
    void clearedDropsSurviveClearRunButNotClear() {
        RunEnd end = new RunEnd();
        end.addClearedDrop("2:a:1");
        end.clearRun();
        assertTrue(end.clearedDrops().contains("2:a:1"));
        end.clear();
        assertTrue(end.clearedDrops().isEmpty());
        assertTrue(end.isEmpty());
    }

    @Test
    void theViewsCannotBeChanged() {
        RunEnd end = new RunEnd();
        assertThrows(UnsupportedOperationException.class, () -> end.deaths().put(steve, Restore.alive(null)));
        assertThrows(UnsupportedOperationException.class, () -> end.positions().put(steve, spot));
        assertThrows(UnsupportedOperationException.class, () -> end.pending().put(steve, Restore.alive(null)));
        assertThrows(UnsupportedOperationException.class, () -> end.clearedDrops().add("x"));
    }

    @Test
    void restoresKeepTheirItemsInSlotOrderAndOutOfReach() {
        Map<Integer, String> items = new HashMap<>(Map.of(40, "c", 0, "a", 7, "b"));
        Restore restore = Restore.dead(spot, items, 12);
        items.put(1, "later");
        assertEquals(3, restore.items().size());
        assertEquals("[0, 7, 40]", restore.items().keySet().toString());
        assertThrows(UnsupportedOperationException.class, () -> restore.items().put(2, "x"));
        assertTrue(restore.hasItems());
        assertTrue(restore.revive());
        assertFalse(Restore.alive(spot).hasItems());
        assertFalse(Restore.alive(spot).revive());
    }
}
