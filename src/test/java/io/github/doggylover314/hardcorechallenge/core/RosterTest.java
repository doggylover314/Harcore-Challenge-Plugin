package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
