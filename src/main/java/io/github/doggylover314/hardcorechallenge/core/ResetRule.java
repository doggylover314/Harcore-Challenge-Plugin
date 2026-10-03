package io.github.doggylover314.hardcorechallenge.core;

import java.util.Locale;
import java.util.Optional;

/**
 * When deaths end a run: on the first death, or once a percentage of the run's participants is dead.
 *
 * @param firstDeath whether any single death ends the run
 * @param percent    1 to 100, the share of participants that must be dead (ignored for first-death)
 */
public record ResetRule(boolean firstDeath, int percent) {
    public static final String FIRST_DEATH = "first-death";

    public static final ResetRule DEFAULT = new ResetRule(true, 100);

    public ResetRule {
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("percent must be 1..100: " + percent);
        }
    }

    public static ResetRule ofPercent(int percent) {
        return new ResetRule(false, percent);
    }

    /** Parses {@code first-death} or {@code N%} (N from 1 to 100); empty for anything else. */
    public static Optional<ResetRule> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (text.equals(FIRST_DEATH)) {
            return Optional.of(DEFAULT);
        }
        if (!text.endsWith("%")) {
            return Optional.empty();
        }
        String digits = text.substring(0, text.length() - 1).trim();
        if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        int percent = Integer.parseInt(digits);
        if (percent < 1 || percent > 100) {
            return Optional.empty();
        }
        return Optional.of(ofPercent(percent));
    }

    /** Deaths needed to end a run with this many participants; never less than one. */
    public int threshold(int participants) {
        if (firstDeath) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(percent * Math.max(0, participants) / 100.0));
    }

    /** Whether this many dead out of this many participants ends the run. */
    public boolean reached(int dead, int participants) {
        return dead >= threshold(participants);
    }

    /** The form used in config.yml and commands. */
    public String configValue() {
        return firstDeath ? FIRST_DEATH : percent + "%";
    }
}
