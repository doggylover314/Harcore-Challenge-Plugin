package io.github.doggylover314.hardcorechallenge.core;

import java.util.Locale;
import java.util.Optional;

/**
 * The four boss objectives a run can be scored against.
 */
public enum Boss {
    ENDER_DRAGON("ender_dragon", "Ender Dragon"),
    WITHER("wither", "Wither"),
    ELDER_GUARDIAN("elder_guardian", "Elder Guardian"),
    WARDEN("warden", "Warden");

    private final String id;
    private final String displayName;

    Boss(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Config / storage identifier, e.g. {@code ender_dragon}. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<Boss> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        for (Boss boss : values()) {
            if (boss.id.equals(normalized)) {
                return Optional.of(boss);
            }
        }
        return Optional.empty();
    }
}
