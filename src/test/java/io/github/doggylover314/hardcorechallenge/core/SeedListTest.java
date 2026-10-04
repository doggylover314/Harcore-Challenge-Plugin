package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doggylover314.hardcorechallenge.core.SeedList.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SeedListTest {
    private static SeedList once(String... seeds) {
        return new SeedList(Mode.ONCE, List.of(seeds), 0);
    }

    private static SeedList cycle(int position, String... seeds) {
        return new SeedList(Mode.CYCLE, List.of(seeds), position);
    }

    /** Takes the next seed and uses it up, like a run that starts. */
    private static String play(SeedList[] list) {
        SeedList picked = list[0].pick();
        String entry = picked.next().orElseThrow();
        list[0] = picked.consume(entry).list();
        return entry;
    }

    // ------------------------------------------------------------------ empty

    @Test
    void anEmptyListHasNothingNext() {
        for (Mode mode : Mode.values()) {
            SeedList empty = SeedList.empty(mode);
            assertTrue(empty.isEmpty());
            assertEquals(0, empty.size());
            assertEquals(-1, empty.nextIndex());
            assertEquals(Optional.empty(), empty.next());
        }
    }

    @Test
    void usingASeedOfAnEmptyListChangesNothing() {
        for (Mode mode : Mode.values()) {
            SeedList empty = SeedList.empty(mode);
            SeedList.Used used = empty.consume("a");
            assertEquals(empty, used.list());
            assertFalse(used.exhausted());
        }
    }

    // ------------------------------------------------------------------- once

    @Test
    void onceTakesTheFirstEntry() {
        SeedList list = once("a", "b", "c");
        assertEquals(Optional.of("a"), list.next());
        assertEquals(0, list.nextIndex());
    }

    @Test
    void onceIgnoresThePosition() {
        assertEquals(Optional.of("a"), new SeedList(Mode.ONCE, List.of("a", "b", "c"), 2).next());
    }

    @Test
    void onceRemovesTheUsedEntry() {
        SeedList.Used used = once("a", "b", "c").consume("a");
        assertEquals(List.of("b", "c"), used.list().seeds());
        assertFalse(used.exhausted());
    }

    @Test
    void onceReportsTheLastSeedBeingUsed() {
        SeedList.Used used = once("a").consume("a");
        assertTrue(used.list().isEmpty());
        assertTrue(used.exhausted());
        assertEquals(Optional.empty(), used.list().next());
    }

    @Test
    void onceExhaustsOnlyWithTheLastOne() {
        SeedList list = once("a", "b");
        SeedList.Used first = list.consume("a");
        assertFalse(first.exhausted());
        SeedList.Used second = first.list().consume("b");
        assertTrue(second.exhausted());
        // Nothing left to use up, so it cannot be reported again.
        assertFalse(second.list().consume("b").exhausted());
    }

    @Test
    void onceRemovesTheFirstOfSeveralEqualEntries() {
        assertEquals(List.of("b", "a"), once("a", "b", "a").consume("a").list().seeds());
    }

    @Test
    void onceWithDuplicatesPlaysEachOne() {
        SeedList[] list = {once("a", "a", "b")};
        assertEquals("a", play(list));
        assertEquals("a", play(list));
        assertEquals("b", play(list));
        assertTrue(list[0].isEmpty());
    }

    @Test
    void onceChangesNothingIfTheEntryIsGone() {
        // The admin edited the list between picking and starting.
        SeedList list = once("x", "y");
        SeedList.Used used = list.consume("a");
        assertEquals(list, used.list());
        assertFalse(used.exhausted());
    }

    @Test
    void onceOnlyRemovesTheEntryThatWasUsed() {
        // "b" was picked, then the admin put "x" in front of it.
        SeedList.Used used = once("x", "b").consume("b");
        assertEquals(List.of("x"), used.list().seeds());
        assertFalse(used.exhausted());
    }

    @Test
    void onceTextAndNumbersAreComparedAsWritten() {
        assertEquals(List.of("42"), once("042", "42").consume("042").list().seeds());
        assertEquals(List.of("042", "42"), once("042", "42").consume("43").list().seeds());
    }

    @Test
    void onceRemovesThePickedEntryNotTheFirstEqualOne() {
        // [a, b, a]: the last "a" was picked.
        SeedList.Used used = new SeedList(Mode.ONCE, List.of("a", "b", "a"), 0, 2).consume("a");
        assertEquals(List.of("a", "b"), used.list().seeds());
        assertFalse(used.exhausted());
    }

    @Test
    void onceKeepsAnEqualEntryWhenThePickedOneWasRemoved() {
        // [a, b, a]: the first "a" was picked, then "/hcc seeds remove 1". The remaining "a" was never played.
        SeedList removed = once("a", "b", "a").pick().remove(0);
        assertEquals(List.of("b", "a"), removed.seeds());
        assertEquals(SeedList.DROPPED, removed.picked());
        SeedList.Used used = removed.consume("a");
        assertEquals(List.of("b", "a"), used.list().seeds());
        assertFalse(used.exhausted());
        assertEquals(-1, used.list().picked());
    }

    @Test
    void onceKeepsAnEqualEntryWhenTheListWasClearedAndRefilled() {
        // "a" was picked, then the list was cleared and "a" added again: the new one was never played.
        SeedList refilled = once("a").pick().clear().add("a");
        SeedList.Used used = refilled.consume("a");
        assertEquals(List.of("a"), used.list().seeds());
        assertFalse(used.exhausted());
    }

    @Test
    void onceUsesTheCyclePickAfterTheModeChanged() {
        // Cycle at position 2 of [a, b, a] picked the last "a", then "/hcc seeds mode once".
        SeedList once = cycle(2, "a", "b", "a").pick().withMode(Mode.ONCE);
        assertEquals(List.of("a", "b"), once.consume("a").list().seeds());
    }

    @Test
    void onceRemovesNothingIfThePickedPlaceHoldsSomethingElse() {
        // The file was edited by hand after the pick.
        SeedList list = new SeedList(Mode.ONCE, List.of("x", "a"), 0, 0);
        assertEquals(List.of("x", "a"), list.consume("a").list().seeds());
    }

    @Test
    void onceFollowsThePickWhenAnEarlierEntryIsRemoved() {
        // [a, b, a]: the last "a" was picked, then the first entry was removed. [b, a] loses its last entry.
        SeedList list = new SeedList(Mode.ONCE, List.of("a", "b", "a"), 0, 2).remove(0);
        assertEquals(List.of("b"), list.consume("a").list().seeds());
    }

    @Test
    void usingAnEntryLeavesTheListUnpicked() {
        SeedList after = new SeedList(Mode.ONCE, List.of("a", "b", "a"), 0, 0).consume("a").list();
        assertEquals(List.of("b", "a"), after.seeds());
        assertEquals(-1, after.picked());
    }

    // ------------------------------------------------------------------ cycle

    @Test
    void cycleStartsAtThePosition() {
        SeedList list = cycle(1, "a", "b", "c");
        assertEquals(Optional.of("b"), list.next());
        assertEquals(1, list.nextIndex());
    }

    @Test
    void aPositionOutsideTheListWraps() {
        assertEquals(1, cycle(7, "a", "b", "c").position());
        assertEquals(2, cycle(-1, "a", "b", "c").position());
        assertEquals(0, cycle(3, "a", "b", "c").position());
        assertEquals(0, cycle(5).position());
    }

    @Test
    void cycleMovesToTheEntryAfterTheUsedOne() {
        SeedList.Used used = cycle(0, "a", "b", "c").pick().consume("a");
        assertEquals(1, used.list().position());
        assertEquals(List.of("a", "b", "c"), used.list().seeds());
        assertFalse(used.exhausted());
    }

    @Test
    void cycleStartsOverAtTheEnd() {
        assertEquals(0, cycle(2, "a", "b", "c").pick().consume("c").list().position());
    }

    @Test
    void cycleGoesRoundAndRound() {
        SeedList[] list = {cycle(0, "a", "b", "c")};
        List<String> played = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            played.add(play(list));
        }
        assertEquals(List.of("a", "b", "c", "a", "b", "c", "a"), played);
    }

    @Test
    void cycleResumesFromASavedPosition() {
        SeedList[] list = {cycle(2, "a", "b", "c")};
        assertEquals("c", play(list));
        assertEquals("a", play(list));
    }

    @Test
    void cycleWithOneSeedAlwaysUsesIt() {
        SeedList[] list = {cycle(0, "a")};
        for (int i = 0; i < 4; i++) {
            assertEquals("a", play(list));
            assertEquals(0, list[0].position());
            assertEquals(1, list[0].size());
        }
    }

    @Test
    void cycleNeverRunsOut() {
        SeedList.Used used = cycle(0, "a").pick().consume("a");
        assertFalse(used.exhausted());
        assertFalse(used.list().isEmpty());
    }

    @Test
    void cycleWithDuplicatesPlaysEveryEntry() {
        SeedList[] list = {cycle(0, "a", "b", "a")};
        List<String> played = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            played.add(play(list));
        }
        assertEquals(List.of("a", "b", "a", "a", "b", "a"), played);
    }

    @Test
    void cycleKeepsThePositionIfTheEntryIsGone() {
        // Whatever is at the position now has not been played.
        SeedList.Used used = cycle(1, "a", "b", "c").pick().consume("zzz");
        assertEquals(cycle(1, "a", "b", "c"), used.list());
        assertFalse(used.exhausted());
    }

    @Test
    void cycleKeepsThePositionIfNothingWasPicked() {
        SeedList.Used used = cycle(0, "a", "b", "c").consume("a");
        assertEquals(cycle(0, "a", "b", "c"), used.list());
        assertFalse(used.exhausted());
    }

    @Test
    void cycleDoesNotSkipAnEntryWhenThePickedOneWasRemoved() {
        // "c" was picked at position 2, then removed: "a" is next and still has to be played.
        SeedList removed = cycle(2, "a", "b", "c").pick().remove(2);
        assertEquals(Optional.of("a"), removed.consume("c").list().next());
    }

    @Test
    void cycleDoesNotSkipAnEntryWhenTheListWasClearedAndRefilled() {
        // "a" was picked, then the list was cleared and refilled: "x" is next and has to be played.
        SeedList refilled = cycle(0, "a", "b", "c").pick().clear().add("x").add("y");
        assertEquals(Optional.of("x"), refilled.consume("a").list().next());
    }

    @Test
    void cycleKeepsThePositionIfThePickedEntryMoved() {
        // Entry 2 was picked, then the file was edited by hand so that it holds something else: "b" has not been played.
        assertEquals(2, new SeedList(Mode.CYCLE, List.of("c", "a", "b"), 2, 2).consume("c").list().position());
        assertEquals(0, new SeedList(Mode.CYCLE, List.of("a", "b", "c"), 0, 0).consume("c").list().position());
    }

    @Test
    void cycleOnlyCountsTheEntryThatWasPicked() {
        // "a" is listed twice; the one that was picked is the one that was used.
        assertEquals(3, cycle(2, "a", "b", "a", "c").pick().consume("a").list().position());
        assertEquals(1, cycle(1, "a", "b", "a", "c").pick().consume("a").list().position());
    }

    @Test
    void cycleDoesNotSkipAnEntryWhenThePickedOneWasRemovedAndListedAgain() {
        // "a" (index 0) was picked and removed while "a" is also listed later: "b" is next and has to be played.
        SeedList removed = cycle(0, "a", "b", "a", "c").pick().remove(0);
        assertEquals(Optional.of("b"), removed.next());
        assertEquals(Optional.of("b"), removed.consume("a").list().next());
    }

    @Test
    void cycleDoesNotSkipAnEntryWhenTheListWasRefilledWithThePickedSeed() {
        // "a" was picked, then the list was cleared and refilled with "x", "a", "y": "x" is next.
        SeedList refilled = cycle(0, "a", "b", "c").pick().clear().add("x").add("a").add("y");
        assertEquals(Optional.of("x"), refilled.consume("a").list().next());
    }

    @Test
    void cycleDoesNotSkipACopyThatTakesThePlaceOfTheRemovedPick() {
        // [a, a, b]: the first "a" was picked and removed. The other "a" is next and has not been played.
        SeedList removed = cycle(0, "a", "a", "b").pick().remove(0);
        SeedList after = removed.consume("a").list();
        assertEquals(0, after.position());
        assertEquals(Optional.of("a"), after.next());
    }

    @Test
    void cycleDoesNotSkipTheCopyThatStartsTheNextRound() {
        // [a, b, a]: the last "a" was picked and removed. The position wraps to the first "a", which is next.
        SeedList removed = cycle(2, "a", "b", "a").pick().remove(2);
        assertEquals(0, removed.position());
        assertEquals(Optional.of("a"), removed.consume("a").list().next());
    }

    @Test
    void cycleDoesNotSkipACopyAddedAfterTheListWasCleared() {
        // "a" was picked, the list was cleared, then "a" and "x" were added: the new "a" is next.
        SeedList refilled = cycle(0, "a", "b").pick().clear().add("a").add("x");
        assertEquals(0, refilled.position());
        assertEquals(Optional.of("a"), refilled.consume("a").list().next());
    }

    @Test
    void cycleStillAdvancesWhenAnEarlierEntryWasRemoved() {
        // "b" was picked at position 1, then "a" was removed: "b" moved to the front and was the one played.
        SeedList removed = cycle(1, "a", "b", "c").pick().remove(0);
        assertEquals(Optional.of("c"), removed.consume("b").list().next());
    }

    @Test
    void cycleStillAdvancesWhenEntriesWereAddedAfterThePick() {
        SeedList added = cycle(2, "a", "b", "c").pick().add("d");
        assertEquals(Optional.of("d"), added.consume("c").list().next());
    }

    @Test
    void cycleStillAdvancesWhenALaterEntryWasRemoved() {
        SeedList removed = cycle(0, "a", "b", "c").pick().remove(2);
        assertEquals(Optional.of("b"), removed.consume("a").list().next());
    }

    // ------------------------------------------------- used while config.yml was broken

    @Test
    void aCyclePickIsRemovedWhenTheListIsOnceByTheTimeItIsUsedUp() {
        // Cycle at position 2 picked "c"; "/hcc seeds mode once"; the run started while config.yml had an error.
        SeedList picked = cycle(2, "a", "b", "c").pick().withMode(Mode.ONCE);
        SeedList.Deferred deferred = SeedList.defer(Mode.ONCE, "c", picked.picked(), picked.position());
        assertEquals(new SeedList.Unconsumed("c", 2), deferred.unconsumed());

        SeedList.Used used = once("a", "b", "c").consume(deferred.unconsumed());
        assertEquals(List.of("a", "b"), used.list().seeds());
        assertFalse(used.exhausted());
    }

    @Test
    void aOncePickOnlyMovesTheCyclePositionWhenTheListIsCycleByTheTimeItIsUsedUp() {
        // "a" was picked from a once list; "/hcc seeds mode cycle"; the run started while config.yml had an error.
        SeedList picked = once("a", "b", "c").pick().withMode(Mode.CYCLE);
        SeedList.Deferred deferred = SeedList.defer(Mode.CYCLE, "a", picked.picked(), picked.position());
        assertEquals(1, deferred.position());
        assertNull(deferred.unconsumed());

        // Nothing is left to use up, so no entry can be taken out of the cycle list.
        SeedList file = cycle(deferred.position(), "a", "b", "c");
        assertEquals(Optional.of("b"), file.next());
        assertEquals(List.of("a", "b", "c"), file.seeds());
    }

    @Test
    void anUnconsumedEntryIsUsedUpInTheListsModeAsItIsNow() {
        // The file was fixed by hand to cycle mode while the entry waited: the cycle only moves on.
        SeedList.Used used = cycle(0, "a", "b", "c").consume(new SeedList.Unconsumed("a", 0));
        assertEquals(List.of("a", "b", "c"), used.list().seeds());
        assertEquals(1, used.list().position());
        assertEquals(Mode.CYCLE, used.list().mode());
        assertFalse(used.exhausted());
    }

    @Test
    void anUnconsumedEntryWithoutARecordedPickIsLookedUpByValue() {
        assertEquals(List.of("b", "a"), once("a", "b", "a").consume(new SeedList.Unconsumed("a", -1)).list().seeds());
        assertEquals(cycle(0, "a", "b"), cycle(0, "a", "b").consume(new SeedList.Unconsumed("a", -1)).list());
    }

    @Test
    void anUnconsumedEntryWhosePickWasDroppedChangesNothing() {
        for (Mode mode : Mode.values()) {
            SeedList file = new SeedList(mode, List.of("a", "b", "a"), 0);
            SeedList.Used used = file.consume(new SeedList.Unconsumed("a", SeedList.DROPPED));
            assertEquals(file, used.list());
            assertFalse(used.exhausted());
        }
    }

    @Test
    void anUnconsumedEntryWhosePlaceHoldsSomethingElseChangesNothing() {
        // The file was edited by hand after the run started.
        SeedList file = once("x", "a");
        assertEquals(file, file.consume(new SeedList.Unconsumed("a", 0)).list());
        assertEquals(file, file.consume(new SeedList.Unconsumed("a", 5)).list());
    }

    @Test
    void usingTheLastUnconsumedEntryOfOnceModeIsReported() {
        SeedList.Used used = once("a").consume(new SeedList.Unconsumed("a", 0));
        assertTrue(used.list().isEmpty());
        assertTrue(used.exhausted());
    }

    @Test
    void usingAnUnconsumedEntryKeepsThePickOfTheRunThatIsStarting() {
        // "c" is picked for the next run when the earlier run's entry "a" is used up.
        SeedList once = new SeedList(Mode.ONCE, List.of("a", "b", "c"), 0, 2);
        SeedList.Used used = once.consume(new SeedList.Unconsumed("a", 0));
        assertEquals(List.of("b", "c"), used.list().seeds());
        assertEquals(1, used.list().picked());
        assertEquals(List.of("b"), used.list().consume("c").list().seeds());

        SeedList cycle = new SeedList(Mode.CYCLE, List.of("a", "b", "c"), 2, 2);
        SeedList.Used moved = cycle.consume(new SeedList.Unconsumed("a", 0));
        assertEquals(2, moved.list().picked());
        assertEquals(0, moved.list().consume("c").list().position());
    }

    @Test
    void anUnconsumedEntryThatIsThePickOfTheRunThatIsStartingDropsThatPick() {
        SeedList once = new SeedList(Mode.ONCE, List.of("a", "b"), 0, 0);
        SeedList.Used used = once.consume(new SeedList.Unconsumed("a", 0));
        assertEquals(List.of("b"), used.list().seeds());
        assertEquals(SeedList.DROPPED, used.list().picked());
        assertEquals(used.list().seeds(), used.list().consume("a").list().seeds());
    }

    @Test
    void whileTheFileIsBrokenACyclePickMovesThePosition() {
        assertEquals(new SeedList.Deferred(2, null), SeedList.defer(Mode.CYCLE, "b", 1, 1));
        assertEquals(new SeedList.Deferred(3, null), SeedList.defer(Mode.CYCLE, "c", 2, 2));
        // Without a pick there is nothing to move past.
        assertEquals(new SeedList.Deferred(4, null), SeedList.defer(Mode.CYCLE, "b", -1, 4));
        assertEquals(new SeedList.Deferred(4, null), SeedList.defer(Mode.CYCLE, "b", SeedList.DROPPED, 4));
    }

    @Test
    void whileTheFileIsBrokenAOncePickIsKeptWithItsPlace() {
        assertEquals(new SeedList.Deferred(3, new SeedList.Unconsumed("a", 0)), SeedList.defer(Mode.ONCE, "a", 0, 3));
        assertEquals(new SeedList.Deferred(3, new SeedList.Unconsumed("a", -1)), SeedList.defer(Mode.ONCE, "a", -1, 3));
        // The entry is no longer in the list, so there is nothing to remove.
        assertEquals(new SeedList.Deferred(3, null), SeedList.defer(Mode.ONCE, "a", SeedList.DROPPED, 3));
    }

    // ------------------------------------------------------------------ pick

    @Test
    void pickRemembersTheNextEntry() {
        assertEquals(1, cycle(1, "a", "b", "c").pick().picked());
        assertEquals(0, once("a", "b", "c").pick().picked());
        // The list itself does not change.
        assertEquals(cycle(1, "a", "b", "c").seeds(), cycle(1, "a", "b", "c").pick().seeds());
        assertEquals(1, cycle(1, "a", "b", "c").pick().position());
    }

    @Test
    void nothingIsPickedFromAnEmptyList() {
        for (Mode mode : Mode.values()) {
            assertEquals(-1, SeedList.empty(mode).pick().picked());
        }
    }

    @Test
    void nothingIsPickedUntilARunTakesAnEntry() {
        assertEquals(-1, cycle(1, "a", "b").picked());
        assertEquals(-1, once("a").picked());
    }

    @Test
    void usingTheSeedForgetsThePick() {
        assertEquals(-1, cycle(0, "a", "b").pick().consume("a").list().picked());
        assertEquals(-1, cycle(0, "a", "b").pick().consume("zzz").list().picked());
        assertEquals(-1, once("a", "b").pick().consume("a").list().picked());
        assertEquals(-1, once("a", "b").pick().consume("zzz").list().picked());
        assertEquals(-1, new SeedList(Mode.ONCE, List.of("a", "b", "c"), 0, 2).consume("a").list().picked());
    }

    @Test
    void thePickFollowsEdits() {
        SeedList picked = cycle(1, "a", "b", "c").pick();
        assertEquals(0, picked.remove(0).picked());
        assertEquals(1, picked.remove(2).picked());
        assertEquals(SeedList.DROPPED, picked.remove(1).picked());
        assertEquals(1, picked.add("d").picked());
        assertEquals(1, picked.withMode(Mode.ONCE).picked());
        assertEquals(SeedList.DROPPED, picked.clear().picked());
    }

    @Test
    void aDroppedPickStaysDroppedThroughOtherEdits() {
        SeedList dropped = cycle(1, "a", "b", "c").pick().remove(1);
        assertEquals(SeedList.DROPPED, dropped.add("d").picked());
        assertEquals(SeedList.DROPPED, dropped.remove(0).picked());
        assertEquals(SeedList.DROPPED, dropped.withMode(Mode.ONCE).picked());
        assertEquals(SeedList.DROPPED, dropped.clear().add("a").picked());
        // Until a run takes an entry again.
        assertEquals(1, dropped.pick().picked());
    }

    @Test
    void clearingAListWithoutAPickDropsNothing() {
        assertEquals(-1, cycle(1, "a", "b").clear().picked());
        assertEquals(-1, once("a").clear().picked());
    }

    @Test
    void aPickOutsideTheListIsDropped() {
        assertEquals(SeedList.DROPPED, new SeedList(Mode.CYCLE, List.of("a", "b"), 0, 2).picked());
        assertEquals(SeedList.DROPPED, new SeedList(Mode.CYCLE, List.of(), 0, 0).picked());
        assertEquals(SeedList.DROPPED, new SeedList(Mode.CYCLE, List.of("a", "b"), 0, SeedList.DROPPED).picked());
        assertEquals(-1, new SeedList(Mode.CYCLE, List.of("a", "b"), 0, -3).picked());
    }

    @Test
    void thePickIsTrackedThroughAnyEdits() {
        // Entries are all different, so the picked one can be found by value afterwards.
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            int size = 1 + random.nextInt(6);
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                entries.add("e" + i);
            }
            SeedList list = new SeedList(Mode.CYCLE, entries, random.nextInt(size)).pick();
            String entry = list.next().orElseThrow();
            int before = list.position();
            int fresh = 0;
            for (int step = random.nextInt(6); step > 0; step--) {
                switch (random.nextInt(3)) {
                    case 0 -> list = list.add("n" + fresh++);
                    case 1 -> list = list.isEmpty() ? list : list.remove(random.nextInt(list.size()));
                    default -> list = random.nextInt(4) == 0 ? list.clear() : list;
                }
            }
            int at = list.seeds().indexOf(entry);
            SeedList after = list.consume(entry).list();
            if (at >= 0) {
                assertEquals((at + 1) % list.size(), after.position());
            } else {
                // The picked entry is gone: whatever is at the position now has not been played.
                assertEquals(list.position(), after.position(), "was " + before);
            }
        }
    }

    // ----------------------------------------------------------------- add

    @Test
    void addAppendsAndKeepsThePosition() {
        SeedList list = cycle(1, "a", "b").add("c");
        assertEquals(List.of("a", "b", "c"), list.seeds());
        assertEquals(1, list.position());
        assertEquals(Mode.CYCLE, list.mode());
    }

    @Test
    void addToAnEmptyListMakesItNext() {
        for (Mode mode : Mode.values()) {
            SeedList list = SeedList.empty(mode).add("a");
            assertEquals(Optional.of("a"), list.next());
            assertEquals(0, list.position());
        }
    }

    @Test
    void addKeepsTheTextAsGiven() {
        assertEquals(List.of("my text seed", "-5"), once().add("my text seed").add("-5").seeds());
    }

    @Test
    void addAllowsDuplicates() {
        assertEquals(List.of("a", "a"), once("a").add("a").seeds());
    }

    @Test
    void addRejectsBlankEntries() {
        assertThrows(IllegalArgumentException.class, () -> once("a").add(""));
        assertThrows(IllegalArgumentException.class, () -> once("a").add("   "));
    }

    // -------------------------------------------------------------- remove

    @Test
    void removeTakesOutTheEntry() {
        assertEquals(List.of("a", "c"), once("a", "b", "c").remove(1).seeds());
        assertEquals(List.of("b", "c"), once("a", "b", "c").remove(0).seeds());
        assertEquals(List.of("a", "b"), once("a", "b", "c").remove(2).seeds());
    }

    @Test
    void removingBeforeThePositionMovesItBack() {
        // Still pointing at "c".
        SeedList list = cycle(2, "a", "b", "c").remove(0);
        assertEquals(1, list.position());
        assertEquals(Optional.of("c"), list.next());
    }

    @Test
    void removingAfterThePositionLeavesItAlone() {
        SeedList list = cycle(1, "a", "b", "c").remove(2);
        assertEquals(1, list.position());
        assertEquals(Optional.of("b"), list.next());
    }

    @Test
    void removingTheNextEntryMakesTheOneAfterItNext() {
        SeedList list = cycle(1, "a", "b", "c").remove(1);
        assertEquals(1, list.position());
        assertEquals(Optional.of("c"), list.next());
    }

    @Test
    void removingTheNextEntryAtTheEndStartsOver() {
        SeedList list = cycle(2, "a", "b", "c").remove(2);
        assertEquals(0, list.position());
        assertEquals(Optional.of("a"), list.next());
    }

    @Test
    void removingTheLastEntryEmptiesTheListAndResetsThePosition() {
        SeedList list = cycle(0, "a").remove(0);
        assertTrue(list.isEmpty());
        assertEquals(0, list.position());
        assertEquals(Optional.empty(), list.next());
    }

    @Test
    void removeRejectsAnIndexOutsideTheList() {
        assertThrows(IndexOutOfBoundsException.class, () -> once("a").remove(1));
        assertThrows(IndexOutOfBoundsException.class, () -> once("a").remove(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> SeedList.empty(Mode.ONCE).remove(0));
    }

    @Test
    void removeOnlyTakesOutTheEntryAtTheIndex() {
        assertEquals(List.of("a", "b"), once("a", "a", "b").remove(1).seeds());
    }

    // --------------------------------------------------------- clear and mode

    @Test
    void clearEmptiesTheListAndResetsThePosition() {
        SeedList list = cycle(2, "a", "b", "c").clear();
        assertTrue(list.isEmpty());
        assertEquals(0, list.position());
        assertEquals(Mode.CYCLE, list.mode());
    }

    @Test
    void changingTheModeKeepsTheEntriesAndPosition() {
        SeedList list = cycle(2, "a", "b", "c").withMode(Mode.ONCE);
        assertEquals(Mode.ONCE, list.mode());
        assertEquals(List.of("a", "b", "c"), list.seeds());
        assertEquals(2, list.position());
        // And back: the cycle carries on where it was.
        assertEquals(Optional.of("c"), list.withMode(Mode.CYCLE).next());
    }

    @Test
    void onceKeepsAnOldCyclePositionValidWhileEntriesAreUsedUp() {
        SeedList list = new SeedList(Mode.ONCE, List.of("a", "b", "c"), 2);
        SeedList after = list.consume("a").list();
        assertEquals(List.of("b", "c"), after.seeds());
        assertEquals(1, after.position());
        assertEquals(Optional.of("c"), after.withMode(Mode.CYCLE).next());
    }

    @Test
    void modesHaveTheirConfigWords() {
        assertEquals("once", Mode.ONCE.configValue());
        assertEquals("cycle", Mode.CYCLE.configValue());
    }

    // ------------------------------------------------------------ immutability

    @Test
    void changesReturnNewListsAndLeaveTheOldOneAlone() {
        SeedList list = cycle(1, "a", "b");
        list.add("c");
        list.remove(0);
        list.clear();
        list.consume("a");
        list.withMode(Mode.ONCE);
        assertEquals(cycle(1, "a", "b"), list);
    }

    @Test
    void theEntriesCannotBeChangedFromOutside() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        SeedList list = new SeedList(Mode.ONCE, source, 0);
        source.add("c");
        assertEquals(List.of("a", "b"), list.seeds());
        assertThrows(UnsupportedOperationException.class, () -> list.seeds().add("c"));
    }

    // ---------------------------------------------------------------- invariants

    @Test
    void thePositionStaysInsideTheListWhateverHappens() {
        Random random = new Random(26);
        for (int round = 0; round < 200; round++) {
            SeedList list = SeedList.empty(random.nextBoolean() ? Mode.ONCE : Mode.CYCLE);
            for (int step = 0; step < 60; step++) {
                switch (random.nextInt(7)) {
                    case 0, 1 -> list = list.add(String.valueOf(random.nextInt(5)));
                    case 2 -> {
                        if (!list.isEmpty()) {
                            list = list.remove(random.nextInt(list.size()));
                        }
                    }
                    case 3 -> list = list.consume(String.valueOf(random.nextInt(5))).list();
                    case 4 -> list = random.nextInt(10) == 0 ? list.clear() : list;
                    case 5 -> {
                        list = list.pick();
                        if (!list.isEmpty()) {
                            assertEquals(list.next().orElseThrow(), list.seeds().get(list.picked()));
                        }
                    }
                    default -> list = list.withMode(random.nextBoolean() ? Mode.ONCE : Mode.CYCLE);
                }
                if (list.isEmpty()) {
                    assertEquals(0, list.position());
                    assertEquals(-1, list.nextIndex());
                    assertTrue(list.picked() < 0);
                } else {
                    assertTrue(list.position() >= 0 && list.position() < list.size());
                    assertTrue(list.picked() >= SeedList.DROPPED && list.picked() < list.size());
                    assertEquals(list.seeds().get(list.nextIndex()), list.next().orElseThrow());
                }
            }
        }
    }
}
