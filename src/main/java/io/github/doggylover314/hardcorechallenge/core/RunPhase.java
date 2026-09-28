package io.github.doggylover314.hardcorechallenge.core;

public enum RunPhase {
    /** No run is active. A world from the previous run may still be loaded for people to look around. */
    IDLE,
    /** A run is being played. */
    RUNNING,
    /** The previous run ended (or a start was requested); the countdown is showing and the next world is being made. */
    RESETTING,
    /** All required bosses fell; everyone is spectating the celebration before the victory action runs. */
    VICTORY
}
