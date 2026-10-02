package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RunQueryTest {
    private static RunLog run(int number, Outcome outcome, String... players) {
        RunLog run = new RunLog(number, number, "hcc_run_" + number, number, null, false);
        for (String player : players) {
            run.addParticipant(UUID.nameUUIDFromBytes(player.getBytes()), player);
        }
        if (outcome != null) {
            run.finish(outcome, number, number, null, null);
        }
        return run;
    }

    private final List<RunLog> runs = List.of(
            run(1, Outcome.DEATH, "Steve"),
            run(2, Outcome.VICTORY, "Steve", "Alex"),
            run(3, Outcome.FORCED_RESET, "Alex"),
            run(4, null, "Alex"));

    private static List<Integer> numbers(List<RunLog> runs) {
        return runs.stream().map(RunLog::runNumber).toList();
    }

    @Test
    void newestFirst() {
        assertEquals(List.of(4, 3, 2, 1), numbers(RunQuery.filter(runs, null, null)));
    }

    @Test
    void byOutcome() {
        assertEquals(List.of(2), numbers(RunQuery.filter(runs, Outcome.VICTORY, null)));
        assertEquals(List.of(1), numbers(RunQuery.filter(runs, Outcome.DEATH, null)));
    }

    @Test
    void byPlayer() {
        assertEquals(List.of(2, 1), numbers(RunQuery.filter(runs, null, "steve")));
        assertEquals(List.of(), numbers(RunQuery.filter(runs, null, "Bob")));
    }

    @Test
    void paging() {
        List<Integer> items = IntStream.rangeClosed(1, 25).boxed().toList();
        RunQuery.Page<Integer> second = RunQuery.page(items, 2, 10);
        assertEquals(List.of(11, 12, 13, 14, 15, 16, 17, 18, 19, 20), second.items());
        assertEquals(3, second.pages());
        assertEquals(25, second.total());

        RunQuery.Page<Integer> beyond = RunQuery.page(items, 99, 10);
        assertEquals(3, beyond.page());
        assertEquals(List.of(21, 22, 23, 24, 25), beyond.items());

        RunQuery.Page<Integer> empty = RunQuery.page(List.of(), 1, 10);
        assertEquals(1, empty.pages());
        assertEquals(List.of(), empty.items());
    }
}
