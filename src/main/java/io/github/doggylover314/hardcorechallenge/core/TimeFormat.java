package io.github.doggylover314.hardcorechallenge.core;

public final class TimeFormat {
    private TimeFormat() {
    }

    /** Formats a duration as {@code H:MM:SS}. */
    public static String clock(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }
}
