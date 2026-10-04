package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SeedsTest {
    @Test
    void wholeNumbersAreUsedAsIs() {
        assertEquals(12345L, Seeds.parse("12345"));
        assertEquals(0L, Seeds.parse("0"));
        assertEquals(7L, Seeds.parse("007"));
    }

    @Test
    void negativeNumbers() {
        assertEquals(-987L, Seeds.parse("-987"));
        assertEquals(-1L, Seeds.parse("-1"));
    }

    @Test
    void textIsHashedLikeVanilla() {
        assertEquals("hello".hashCode(), Seeds.parse("hello"));
        assertEquals(96354L, Seeds.parse("abc"));
        // Spaces inside the text are part of it.
        assertEquals("hello world".hashCode(), Seeds.parse("hello world"));
    }

    @Test
    void surroundingSpacesAreIgnored() {
        assertEquals(42L, Seeds.parse("  42  "));
        assertEquals(-42L, Seeds.parse("\t-42\n"));
        assertEquals("hello".hashCode(), Seeds.parse("  hello  "));
        assertEquals("hello world".hashCode(), Seeds.parse(" hello world "));
    }

    @Test
    void theLimitsOfALong() {
        assertEquals(Long.MAX_VALUE, Seeds.parse(String.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE, Seeds.parse(String.valueOf(Long.MIN_VALUE)));
    }

    @Test
    void aNumberTooBigForALongIsText() {
        String tooBig = "9223372036854775808";
        assertEquals(tooBig.hashCode(), Seeds.parse(tooBig));
        String tooSmall = "-9223372036854775809";
        assertEquals(tooSmall.hashCode(), Seeds.parse(tooSmall));
        String huge = "123456789012345678901234567890";
        assertEquals(huge.hashCode(), Seeds.parse(huge));
    }

    @Test
    void otherNumberFormatsAreText() {
        for (String text : new String[] {"1.5", "1e3", "0x10", "12 34", "1,000", "--1"}) {
            assertEquals(text.hashCode(), Seeds.parse(text), text);
        }
    }

    @Test
    void emptyTextHashesToZero() {
        assertEquals(0L, Seeds.parse(""));
        assertEquals(0L, Seeds.parse("   "));
    }
}
