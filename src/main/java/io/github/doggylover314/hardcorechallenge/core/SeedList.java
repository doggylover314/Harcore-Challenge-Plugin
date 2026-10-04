package io.github.doggylover314.hardcorechallenge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The seeds an admin preloaded for the next runs. Immutable: every change returns a new list.
 *
 * @param mode     what happens to a seed once its run has started
 * @param seeds    the entries as the admin wrote them, in order
 * @param position cycle mode: index of the entry that is next. Always inside the list (0 if it is empty).
 * @param picked   index of the entry a run has taken but that is not used up yet (its run has not started),
 *                 -1 if no run has taken one, or {@link #DROPPED} if an edit removed it (removing the entry or
 *                 clearing the list). Other edits keep it on that entry.
 */
public record SeedList(Mode mode, List<String> seeds, int position, int picked) {
    /** {@link #picked} when the picked entry was removed by an edit; the run's seed is no longer in the list. */
    public static final int DROPPED = -2;

    public enum Mode {
        /** Each seed is played one time, in order, then removed. */
        ONCE,
        /** Go through the list in order and start over at the end. */
        CYCLE;

        /** The word used in config.yml. */
        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A seed that has been used.
     *
     * @param list      the list afterwards
     * @param exhausted that was the last seed of a once-mode list
     */
    public record Used(SeedList list, boolean exhausted) {
    }

    /**
     * A used seed that could not be taken out of the list yet, because config.yml had an error and
     * the file's own mode and entries were unknown.
     *
     * @param entry  the entry as written in the list
     * @param picked the entry's index when it was used, -1 if not recorded or {@link #DROPPED}
     */
    public record Unconsumed(String entry, int picked) {
    }

    /**
     * What a started run leaves behind while config.yml has an error.
     *
     * @param position   where a cycle-mode list stands afterwards
     * @param unconsumed the entry to use up once the file can be read again, or null if there is none
     */
    public record Deferred(int position, Unconsumed unconsumed) {
    }

    public SeedList {
        Objects.requireNonNull(mode, "mode");
        seeds = List.copyOf(seeds);
        // A position that no longer fits (the list was edited by hand) wraps around.
        position = seeds.isEmpty() ? 0 : Math.floorMod(position, seeds.size());
        // A pick that no longer fits (the list was edited by hand) counts as dropped.
        picked = picked >= 0 && picked < seeds.size() ? picked : picked >= 0 || picked == DROPPED ? DROPPED : -1;
    }

    public SeedList(Mode mode, List<String> seeds, int position) {
        this(mode, seeds, position, -1);
    }

    public static SeedList empty(Mode mode) {
        return new SeedList(mode, List.of(), 0);
    }

    public boolean isEmpty() {
        return seeds.isEmpty();
    }

    public int size() {
        return seeds.size();
    }

    /** Index of the entry the next run takes, or -1 if the list is empty. */
    public int nextIndex() {
        if (seeds.isEmpty()) {
            return -1;
        }
        return mode == Mode.CYCLE ? position : 0;
    }

    /** The entry the next run takes. Empty if the list is empty. */
    public Optional<String> next() {
        int index = nextIndex();
        return index < 0 ? Optional.empty() : Optional.of(seeds.get(index));
    }

    /** A run takes the next entry. It stays in the list until {@link #consume} says its run has started. */
    public SeedList pick() {
        return new SeedList(mode, seeds, position, nextIndex());
    }

    /** Adds an entry to the end. The position stays where it is. */
    public SeedList add(String entry) {
        if (entry.isBlank()) {
            throw new IllegalArgumentException("A seed list entry cannot be blank");
        }
        List<String> changed = new ArrayList<>(seeds);
        changed.add(entry);
        return new SeedList(mode, changed, position, picked);
    }

    /**
     * Removes the entry at the index (0-based). The position keeps pointing at the same entry; if
     * that was the removed one, it points at the entry after it, or the start if that was the last.
     * If the picked entry is removed, the pick is dropped.
     */
    public SeedList remove(int index) {
        Objects.checkIndex(index, seeds.size());
        List<String> changed = new ArrayList<>(seeds);
        changed.remove(index);
        int pick = index == picked ? DROPPED : index < picked ? picked - 1 : picked;
        return new SeedList(mode, changed, index < position ? position - 1 : position, pick);
    }

    /** Removes every entry. A pick that was taken is dropped. */
    public SeedList clear() {
        return new SeedList(mode, List.of(), 0, picked);
    }

    public SeedList withMode(Mode newMode) {
        return new SeedList(newMode, seeds, position, picked);
    }

    /**
     * A run that took this entry has started. Nothing is picked afterwards.
     * <ul>
     *   <li>once: the picked entry is removed if it is still in the list; if the pick was dropped by an
     *       edit, nothing is removed. Only if no pick was recorded, the first entry equal to it is removed</li>
     *   <li>cycle: if the picked entry is still in the list, the position moves on to the entry after
     *       it; otherwise the position stays, since the entry there has not been played</li>
     * </ul>
     * The list may have been edited since the entry was picked, so it is checked again. The picked
     * entry is tracked by place, not by value, so another entry with the same seed does not count.
     * The value is still compared, in case the file was edited by hand.
     */
    public Used consume(String entry) {
        Used used = use(entry, picked);
        return new Used(used.list().unpicked(), used.exhausted());
    }

    /**
     * Uses up an entry that could not be used up when its run started, with the pick it had. Like any
     * entry it is used up in the list's mode as it is now, since the admin may have changed it. The
     * list's own pick (a run that is starting now) stays, and follows the removal.
     */
    public Used consume(Unconsumed unconsumed) {
        return use(unconsumed.entry(), unconsumed.picked());
    }

    /**
     * A run that took the entry has started, but the list in config.yml can't be read. Cycle mode only
     * moves the position, which lives in state.yml. A once-mode entry can't be removed yet, so it is kept to
     * be used up later; not if its pick was dropped by an edit, since the run's seed is no longer in the list.
     *
     * @param mode     the list's mode as last known
     * @param entry    the entry as written in the list
     * @param picked   the entry's index when it was picked
     * @param position where a cycle-mode list stands
     */
    public static Deferred defer(Mode mode, String entry, int picked, int position) {
        if (mode == Mode.CYCLE) {
            return new Deferred(picked >= 0 ? picked + 1 : position, null);
        }
        return new Deferred(position, picked == DROPPED ? null : new Unconsumed(entry, picked));
    }

    /** Uses up an entry that was picked at the index (-1 if no pick was recorded, or {@link #DROPPED}). */
    private Used use(String entry, int usedPick) {
        if (seeds.isEmpty()) {
            return new Used(this, false);
        }
        boolean found = usedPick >= 0 && usedPick < seeds.size() && seeds.get(usedPick).equals(entry);
        if (mode == Mode.ONCE) {
            int index = usedPick >= 0 ? (found ? usedPick : -1) : usedPick == DROPPED ? -1 : seeds.indexOf(entry);
            if (index < 0) {
                return new Used(this, false);
            }
            SeedList rest = remove(index);
            return new Used(rest, rest.isEmpty());
        }
        return new Used(found ? new SeedList(mode, seeds, usedPick + 1, picked) : this, false);
    }

    private SeedList unpicked() {
        return new SeedList(mode, seeds, position);
    }
}
