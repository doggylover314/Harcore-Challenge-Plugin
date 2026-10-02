package io.github.doggylover314.hardcorechallenge.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Filtering and paging for the runs list.
 */
public final class RunQuery {
    private RunQuery() {
    }

    public record Page<T>(List<T> items, int page, int pages, int total) {
    }

    /**
     * @param outcome only runs that ended this way, or {@code null} for all
     * @param player  only runs this player took part in, or {@code null} for all
     * @return matching runs, newest first
     */
    public static List<RunLog> filter(Collection<RunLog> runs, Outcome outcome, String player) {
        List<RunLog> result = new ArrayList<>();
        for (RunLog run : runs) {
            if (outcome != null && run.outcome() != outcome) {
                continue;
            }
            if (player != null && !run.involves(player)) {
                continue;
            }
            result.add(run);
        }
        result.sort(Comparator.comparingInt(RunLog::runNumber).reversed());
        return result;
    }

    /** One page (1-based, clamped to the valid range) of a list. */
    public static <T> Page<T> page(List<T> items, int page, int perPage) {
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        int current = Math.max(1, Math.min(page, pages));
        int from = (current - 1) * perPage;
        int to = Math.min(items.size(), from + perPage);
        return new Page<>(List.copyOf(items.subList(Math.min(from, to), to)), current, pages, items.size());
    }
}
