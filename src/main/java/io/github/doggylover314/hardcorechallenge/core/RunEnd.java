package io.github.doggylover314.hardcorechallenge.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What is recorded while a run is played and when it ends, so the run can be continued afterwards:
 * where and with what each participant died, where the others were, and what offline participants still
 * get back when they join, and which death drops were given back and must not stay on the ground. Cleared when a new run starts.
 */
public final class RunEnd {
    private final Map<UUID, Restore> deaths = new LinkedHashMap<>();
    private final Map<UUID, Spot> positions = new LinkedHashMap<>();
    private final Map<UUID, Restore> pending = new LinkedHashMap<>();
    private final Set<String> clearedDrops = new LinkedHashSet<>();
    private boolean startedOver;

    /** A participant who is out of the run: where they died and (percentage mode) what they had. */
    public void recordDeath(UUID id, Restore death) {
        deaths.put(id, death);
    }

    /** They are back in the run some other way (revived), so their death no longer counts. */
    public void forgetDeath(UUID id) {
        deaths.remove(id);
    }

    /** Where a participant who was still playing was when the run ended. */
    public void recordPosition(UUID id, Spot spot) {
        positions.put(id, spot);
    }

    /** Waiting for the participant: applied when they are online (right away or when they join). */
    public void putPending(UUID id, Restore restore) {
        pending.put(id, restore);
    }

    public Restore pending(UUID id) {
        return pending.get(id);
    }

    public void removePending(UUID id) {
        pending.remove(id);
    }

    /**
     * Marks what a death dropped as given back with the inventory, so those items and orbs are removed
     * wherever and whenever they turn up (chunks and their entities may load long after the continue).
     */
    public void addClearedDrop(String tag) {
        clearedDrops.add(tag);
    }

    /** A new run was started over this one (/hcc start): the roster and the records above are gone, so it can't be continued. */
    public void markStartedOver() {
        startedOver = true;
    }

    public boolean startedOver() {
        return startedOver;
    }

    /** Forgets the deaths and positions of the run, e.g. once they have been turned into pending restores. */
    public void clearRun() {
        deaths.clear();
        positions.clear();
    }

    /** Forgets everything; a new run starts. */
    public void clear() {
        clearRun();
        pending.clear();
        clearedDrops.clear();
        startedOver = false;
    }

    public boolean isEmpty() {
        return deaths.isEmpty() && positions.isEmpty() && pending.isEmpty() && clearedDrops.isEmpty() && !startedOver;
    }

    public Map<UUID, Restore> deaths() {
        return Collections.unmodifiableMap(deaths);
    }

    public Map<UUID, Spot> positions() {
        return Collections.unmodifiableMap(positions);
    }

    public Map<UUID, Restore> pending() {
        return Collections.unmodifiableMap(pending);
    }

    public Set<String> clearedDrops() {
        return Collections.unmodifiableSet(clearedDrops);
    }
}
