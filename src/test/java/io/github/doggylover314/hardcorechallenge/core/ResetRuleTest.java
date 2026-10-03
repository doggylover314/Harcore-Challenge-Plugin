package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResetRuleTest {
    @Test
    void parsesFirstDeathAndPercentages() {
        assertEquals(Optional.of(ResetRule.DEFAULT), ResetRule.parse("first-death"));
        assertEquals(Optional.of(ResetRule.DEFAULT), ResetRule.parse(" First_Death "));
        assertEquals(Optional.of(ResetRule.ofPercent(50)), ResetRule.parse("50%"));
        assertEquals(Optional.of(ResetRule.ofPercent(1)), ResetRule.parse("1%"));
        assertEquals(Optional.of(ResetRule.ofPercent(100)), ResetRule.parse("100%"));
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[] {null, "", "0%", "101%", "-5%", "50", "%", "5.5%", "abc%", "everyone-dead", "1000%"}) {
            assertTrue(ResetRule.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void thresholdRoundsUp() {
        ResetRule half = ResetRule.ofPercent(50);
        assertEquals(2, half.threshold(3));
        assertEquals(2, half.threshold(4));
        assertEquals(3, half.threshold(5));
        assertEquals(1, ResetRule.ofPercent(10).threshold(3));
        assertEquals(1, half.threshold(1));
    }

    @Test
    void thresholdIsAtLeastOne() {
        assertEquals(1, ResetRule.ofPercent(1).threshold(0));
        assertEquals(1, ResetRule.DEFAULT.threshold(10));
    }

    @Test
    void oneHundredPercentMeansEveryone() {
        ResetRule all = ResetRule.ofPercent(100);
        assertEquals(4, all.threshold(4));
        assertFalse(all.reached(3, 4));
        assertTrue(all.reached(4, 4));
    }

    @Test
    void aLateJoinerRaisesTheTotal() {
        ResetRule half = ResetRule.ofPercent(50);
        assertTrue(half.reached(2, 4));
        // A fifth player joins: two deaths are no longer enough.
        assertFalse(half.reached(2, 5));
        assertTrue(half.reached(3, 5));
    }

    @Test
    void firstDeathNeedsOneDeath() {
        assertFalse(ResetRule.DEFAULT.reached(0, 5));
        assertTrue(ResetRule.DEFAULT.reached(1, 5));
    }

    @Test
    void configValueRoundTrips() {
        assertEquals("first-death", ResetRule.DEFAULT.configValue());
        assertEquals("75%", ResetRule.ofPercent(75).configValue());
        assertEquals(Optional.of(ResetRule.ofPercent(75)), ResetRule.parse(ResetRule.ofPercent(75).configValue()));
    }
}
