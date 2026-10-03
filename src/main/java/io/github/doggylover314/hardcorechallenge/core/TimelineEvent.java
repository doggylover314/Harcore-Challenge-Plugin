package io.github.doggylover314.hardcorechallenge.core;

import java.util.Objects;

/**
 * One entry in a run's timeline.
 *
 * @param elapsedMillis run time when it happened
 * @param at            epoch millis when it happened
 * @param type          what happened
 * @param player        player involved, or {@code null}
 * @param detail        extra info (boss id, dimension, item, structure, reason), or {@code null}
 */
public record TimelineEvent(long elapsedMillis, long at, Type type, String player, String detail) {

    public enum Type {
        RUN_STARTED,
        JOINED,
        LEFT,
        PARTICIPANT_ADDED,
        DIMENSION_FIRST,
        MILESTONE,
        STRUCTURE_FIRST,
        BOSS_FIGHT_STARTED,
        BOSS_KILLED,
        ELIMINATED,
        REVIVED,
        DEATH,
        RUN_ENDED
    }

    public TimelineEvent {
        Objects.requireNonNull(type, "type");
    }
}
