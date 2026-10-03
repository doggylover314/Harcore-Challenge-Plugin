package io.github.doggylover314.hardcorechallenge.config;

import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.ResetRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml. A new one is built on every reload.
 */
public record Settings(
        ResetRule resetWhen,
        int resetCountdownSeconds,
        int spawnProtectionSeconds,
        int keepOldWorlds,
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
