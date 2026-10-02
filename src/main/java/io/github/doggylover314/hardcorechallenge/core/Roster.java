package io.github.doggylover314.hardcorechallenge.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is taking part in the challenge, who is out of the current run, and which run each
 * participant's inventory and position belong to.
 */
public final class Roster {
    private final Map<UUID, String> participants = new LinkedHashMap<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private final Map<UUID, Integer> syncedRun = new HashMap<>();

    public boolean add(UUID id, String name) {
        boolean added = !participants.containsKey(id);
        participants.put(id, name);
        return added;
    }

    public boolean remove(UUID id) {
        eliminated.remove(id);
        syncedRun.remove(id);
        return participants.remove(id) != null;
    }

    public void updateName(UUID id, String name) {
        participants.computeIfPresent(id, (key, old) -> name);
    }

    public boolean isParticipant(UUID id) {
        return participants.containsKey(id);
    }

    /** A participant who is still in the current run. */
    public boolean isActive(UUID id) {
        return participants.containsKey(id) && !eliminated.contains(id);
    }

    public void eliminate(UUID id) {
        if (participants.containsKey(id)) {
            eliminated.add(id);
        }
    }

    public boolean isEliminated(UUID id) {
        return eliminated.contains(id);
    }

    public void clearEliminations() {
        eliminated.clear();
    }

    /** Records that this participant's player state now belongs to the given run. */
    public void markSynced(UUID id, int runNumber) {
        syncedRun.put(id, runNumber);
    }

    /** Whether the participant still carries state from an older run (e.g. they were offline during a reset). */
    public boolean needsSync(UUID id, int runNumber) {
        Integer synced = syncedRun.get(id);
        return synced == null || synced != runNumber;
    }

    public Integer syncedRun(UUID id) {
        return syncedRun.get(id);
    }

    public Map<UUID, String> participants() {
        return Collections.unmodifiableMap(participants);
    }

    public Set<UUID> eliminated() {
        return Collections.unmodifiableSet(eliminated);
    }

    public Map<UUID, Integer> syncedRuns() {
        return Collections.unmodifiableMap(syncedRun);
    }

    public boolean isEmpty() {
        return participants.isEmpty();
    }

    public int size() {
        return participants.size();
    }

    public void clear() {
        participants.clear();
        eliminated.clear();
        syncedRun.clear();
    }
}
