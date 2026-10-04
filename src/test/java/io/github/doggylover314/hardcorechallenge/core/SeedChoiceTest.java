package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeedChoiceTest {
    @Test
    void aListChoiceKeepsTheEntryAndItsSeed() {
        SeedChoice choice = SeedChoice.fromList(" 123 ");
        assertTrue(choice.fromList());
        assertEquals(" 123 ", choice.listEntry());
        assertEquals(123L, choice.seed());
        assertNull(choice.replayOf());
        assertFalse(choice.custom());
    }

    @Test
    void theListModeCanBeChangedAfterThePick() {
        SeedChoice once = SeedChoice.fromList("12", SeedList.Mode.ONCE);
        assertEquals(SeedChoice.fromList("12", SeedList.Mode.CYCLE), once.withListMode(SeedList.Mode.CYCLE));
        assertEquals(SeedList.Mode.ONCE, once.listMode());
    }

    @Test
    void textEntriesAreHashed() {
        assertEquals("my seed".hashCode(), SeedChoice.fromList("my seed").seed());
    }

    @Test
    void otherChoicesAreNotFromTheList() {
        assertFalse(SeedChoice.RANDOM.fromList());
        assertFalse(new SeedChoice(5L, null, true).fromList());
        assertFalse(new SeedChoice(5L, 2, false).fromList());
    }
}
