package io.github.doggylover314.hardcorechallenge.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is taking part in the challenge, who is actually in the current run, who is out of it, and
 * which run each participant's inventory and position belong to.
 *
 * <p>The participants are everyone who has joined the challenge since the last /hcc start. The
 * "in run" set is the subset that was moved into the current run (at its start or later, when they
 * joined); it is cleared whenever a new run begins.</p>
 */
public final class Roster {
    private final Map<UUID, String> participants = new LinkedHashMap<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private final Set<UUID> inRun = new LinkedHashSet<>();
    private final Map<UUID, Integer> syncedRun = new HashMap<>();

    public boolean add(UUID id, String name) {
        boolean added = !participants.containsKey(id);
        participants.put(id, name);
        return added;
    }

    public boolean remove(UUID id) {
        eliminated.remove(id);
        inRun.remove(id);
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

    /** Un-eliminates a participant. Returns whether they were out. */
    public boolean revive(UUID id) {
        return eliminated.remove(id);
    }

    public void clearEliminations() {
        eliminated.clear();
    }

    /** A new run begins: nobody is out and nobody is in it yet. */
    public void beginRun() {
        eliminated.clear();
        inRun.clear();
    }

    /** Marks a participant as being in the current run. Returns false if they aren't a participant. */
    public boolean joinRun(UUID id) {
        if (!participants.containsKey(id)) {
            return false;
        }
        inRun.add(id);
        return true;
    }

    public boolean isInRun(UUID id) {
        return inRun.contains(id);
    }

    /** Participants of the current run that haven't been eliminated, online or not. */
    public int aliveInRun() {
        int alive = 0;
        for (UUID id : inRun) {
            if (!eliminated.contains(id)) {
                alive++;
            }
        }
        return alive;
    }

    /** Participants of the current run that have been eliminated. */
    public int deadInRun() {
        return inRun.size() - aliveInRun();
    }

    /** Number of participants in the current run, eliminated or not. */
    public int runSize() {
        return inRun.size();
    }

    /** The participants of the current run (id to name), in the order they joined it. */
    public Map<UUID, String> runParticipants() {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (UUID id : inRun) {
            String name = participants.get(id);
            if (name != null) {
                result.put(id, name);
            }
        }
        return result;
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

    public Set<UUID> inRun() {
        return Collections.unmodifiableSet(inRun);
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
        inRun.clear();
        syncedRun.clear();
    }
}
