package io.github.doggylover314.hardcorechallenge.config;

import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.ResetRule;
import io.github.doggylover314.hardcorechallenge.core.SeedList;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Immutable snapshot of config.yml. A new one is built on every reload.
 */
public record Settings(
        ResetRule resetWhen,
        int resetCountdownSeconds,
        int spawnProtectionSeconds,
        int keepOldWorlds,
        SeedList.Mode seedMode,
        List<String> seeds,
        List<Boss> bosses,
        boolean announceChat,
        boolean announceTitle,
        boolean announceBossbar,
        boolean announceSound,
        boolean victoryEnabled,
        boolean victoryRequireAllBosses,
        VictoryAction victoryAction,
        int victoryFreezeSeconds,
        boolean victoryFireworks,
        ConfigurationSection sounds
) {
    public enum VictoryAction { STOP, RESET }

    public static Settings load(FileConfiguration config, Logger logger) {
        List<Boss> bosses = new ArrayList<>();
        for (String id : config.getStringList("bosses")) {
            Boss.fromId(id).ifPresentOrElse(
                    boss -> {
                        if (!bosses.contains(boss)) {
                            bosses.add(boss);
                        }
                    },
                    () -> logger.warning("Unknown boss '" + id + "' in config.yml; expected one of ender_dragon, wither, elder_guardian, warden"));
        }
        if (bosses.isEmpty()) {
            logger.warning("No valid bosses configured; the checklist is empty and a run can only end by death or command.");
        }

        return new Settings(
                parseResetWhen(config.getString("reset-when"), logger),
                Math.max(0, config.getInt("reset-countdown-seconds", 10)),
                Math.max(0, config.getInt("spawn-protection-seconds", 60)),
                Math.max(0, config.getInt("keep-old-worlds", 0)),
                readSeedMode(config, logger),
                readSeeds(config, logger),
                List.copyOf(bosses),
                config.getBoolean("announce.chat", true),
                config.getBoolean("announce.title", true),
                config.getBoolean("announce.bossbar", true),
                config.getBoolean("announce.sound", true),
                config.getBoolean("victory.enabled", true),
                config.getBoolean("victory.require-all-bosses", true),
                parseEnum(VictoryAction.class, config.getString("victory.action"), VictoryAction.STOP, "victory.action", logger),
                Math.max(0, config.getInt("victory.freeze-seconds", 30)),
                config.getBoolean("victory.fireworks", true),
                config.getConfigurationSection("sounds")
        );
    }

    /** {@code seed-list.mode}; anything but once or cycle is a mistake and counts as once. */
    public static SeedList.Mode readSeedMode(FileConfiguration config, Logger logger) {
        return parseEnum(SeedList.Mode.class, config.getString("seed-list.mode"), SeedList.Mode.ONCE, "seed-list.mode", logger);
    }

    /**
     * {@code seed-list.seeds} as text. Blank entries are dropped. YAML turns some unquoted text into
     * other types (yes, 1.5, 2024-01-01), so those are warned about; they should be in quotes.
     * A single value without a list counts as a list of one.
     */
    public static List<String> readSeeds(FileConfiguration config, Logger logger) {
        List<String> seeds = new ArrayList<>();
        Object value = config.get("seed-list.seeds");
        List<?> raw = List.of();
        if (value instanceof List<?> list) {
            raw = list;
        } else if (value instanceof ConfigurationSection) {
            logger.warning("seed-list.seeds must be a list, like [12345, \"my text seed\"]. Ignoring it");
        } else if (value != null) {
            raw = List.of(value);
        }
        for (Object entry : raw) {
            if (entry == null) {
                logger.warning("Ignoring an empty entry in seed-list.seeds. Put text seeds like null in quotes");
                continue;
            }
            if (!(entry instanceof String || entry instanceof Integer || entry instanceof Long || entry instanceof BigInteger)) {
                logger.warning("The seed-list.seeds entry '" + entry + "' was read as a " + entry.getClass().getSimpleName()
                        + ", not as text. Put it in quotes to use it as written");
            }
            // Plain numbers (even ones too big for a long) keep their digits as the text.
            if (!entry.toString().isBlank()) {
                seeds.add(entry.toString());
            }
        }
        return seeds;
    }

    /**
     * Whether the file parses as YAML. Bukkit loads a broken file as an empty config, which must
     * never be saved over the real one.
     */
    public static boolean isReadable(Path file, Logger logger) {
        try {
            new YamlConfiguration().load(file.toFile());
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Could not read " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    public String sound(String key) {
        return sounds == null ? null : sounds.getString(key);
    }

    static ResetRule parseResetWhen(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return ResetRule.DEFAULT;
        }
        return ResetRule.parse(raw).orElseGet(() -> {
            logger.warning("Invalid value '" + raw + "' for reset-when; expected first-death or a percentage from 1% to 100%. Using first-death");
            return ResetRule.DEFAULT;
        });
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, String path, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid value '" + raw + "' for " + path + "; using " + fallback.name().toLowerCase(Locale.ROOT));
            return fallback;
        }
    }
}
