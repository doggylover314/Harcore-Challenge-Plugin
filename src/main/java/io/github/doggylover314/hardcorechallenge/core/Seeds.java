package io.github.doggylover314.hardcorechallenge.core;

/** Turns the text of a seed into the number a world is generated from. */
public final class Seeds {
    private Seeds() {
    }

    /**
     * A whole number is used as is. Anything else is hashed with {@link String#hashCode()}, which is
     * what vanilla does with a text seed. Surrounding spaces are ignored.
     */
    public static long parse(String raw) {
        String text = raw.trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }
}
