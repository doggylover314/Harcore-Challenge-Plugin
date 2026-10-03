package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RosterTest {
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();

    @Test
    void addAndRemove() {
        Roster roster = new Roster();
        assertTrue(roster.add(steve, "Steve"));
        assertFalse(roster.add(steve, "Steve"));
        assertTrue(roster.isParticipant(steve));
        assertTrue(roster.remove(steve));
        assertFalse(roster.isParticipant(steve));
        assertFalse(roster.remove(steve));
    }

    @Test
    void eliminationOnlyAppliesToParticipants() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.eliminate(steve);
        roster.eliminate(alex);
        assertFalse(roster.isActive(steve));
        assertTrue(roster.isEliminated(steve));
        assertFalse(roster.isEliminated(alex));
        roster.clearEliminations();
        assertTrue(roster.isActive(steve));
    }

    @Test
    void offlineParticipantsNeedSyncAfterAReset() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.add(alex, "Alex");
        roster.markSynced(steve, 3);
        roster.markSynced(alex, 3);

        // Run 4 starts while Alex is offline; only Steve is moved over.
        roster.markSynced(steve, 4);
        assertFalse(roster.needsSync(steve, 4));
        assertTrue(roster.needsSync(alex, 4));
    }

    @Test
    void namesUpdateButOnlyForParticipants() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.updateName(steve, "Steve2");
        roster.updateName(alex, "Alex");
        assertEquals("Steve2", roster.participants().get(steve));
        assertFalse(roster.isParticipant(alex));
    }

    @Test
    void onlyPlayersMovedIntoTheRunCount() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.add(alex, "Alex");
        assertEquals(0, roster.runSize());
        assertFalse(roster.joinRun(UUID.randomUUID()));

        assertTrue(roster.joinRun(steve));
        assertTrue(roster.isInRun(steve));
        assertFalse(roster.isInRun(alex));
        assertEquals(1, roster.runSize());
        assertEquals(Map.of(steve, "Steve"), roster.runParticipants());
    }

    @Test
    void aliveAndDeadCountOnlyTheRun() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.add(alex, "Alex");
        roster.joinRun(steve);
        roster.joinRun(alex);
        roster.eliminate(alex);
        assertEquals(1, roster.aliveInRun());
        assertEquals(1, roster.deadInRun());

        assertTrue(roster.revive(alex));
        assertFalse(roster.revive(alex));
        assertEquals(2, roster.aliveInRun());
        assertEquals(0, roster.deadInRun());
    }

    @Test
    void beginningARunEmptiesIt() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.add(alex, "Alex");
        roster.joinRun(steve);
        roster.joinRun(alex);
        roster.eliminate(alex);

        roster.beginRun();
        assertEquals(0, roster.runSize());
        assertTrue(roster.isParticipant(alex));
        assertTrue(roster.isActive(alex));
        assertTrue(roster.runParticipants().isEmpty());
    }

    @Test
    void removedParticipantsLeaveTheRun() {
        Roster roster = new Roster();
        roster.add(steve, "Steve");
        roster.joinRun(steve);
        roster.remove(steve);
        assertEquals(0, roster.runSize());
        roster.clear();
        assertTrue(roster.inRun().isEmpty());
    }
}
