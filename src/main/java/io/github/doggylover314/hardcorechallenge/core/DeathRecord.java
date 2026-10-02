package io.github.doggylover314.hardcorechallenge.core;

import java.util.Objects;
import java.util.UUID;

/**
 * The death that ended (or would have ended) a run.
 *
 * @param playerId   the player who died
 * @param playerName their name at the time
 * @param cause      readable damage cause, e.g. {@code lava}
 * @param killer     readable name of the causing entity, or {@code null}
 * @param message    plain-text vanilla death message, or {@code null}
 * @param world      world name
 * @param x          block x
 * @param y          block y
 * @param z          block z
 */
public record DeathRecord(
        UUID playerId,
        String playerName,
        String cause,
        String killer,
        String message,
        String world,
        int x,
        int y,
        int z
) {
    public DeathRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(cause, "cause");
    }
}
