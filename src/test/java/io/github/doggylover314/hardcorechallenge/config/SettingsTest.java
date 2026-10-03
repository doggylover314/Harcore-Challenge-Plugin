package io.github.doggylover314.hardcorechallenge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doggylover314.hardcorechallenge.core.ResetRule;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {
    private static final Logger LOGGER = Logger.getLogger("SettingsTest");

    private static Settings load(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return Settings.load(config, LOGGER);
    }

    @Test
    void defaults() throws Exception {
        Settings settings = load("");
        assertEquals(ResetRule.DEFAULT, settings.resetWhen());
        assertEquals(60, settings.spawnProtectionSeconds());
    }

    @Test
    void readsResetWhen() throws Exception {
        assertEquals(ResetRule.ofPercent(50), load("reset-when: 50%").resetWhen());
        assertEquals(ResetRule.ofPercent(100), load("reset-when: \"100%\"").resetWhen());
        assertEquals(ResetRule.DEFAULT, load("reset-when: first-death").resetWhen());
    }

    @Test
    void invalidResetWhenFallsBackToFirstDeath() throws Exception {
        assertEquals(ResetRule.DEFAULT, load("reset-when: 0%").resetWhen());
        assertEquals(ResetRule.DEFAULT, load("reset-when: 150%").resetWhen());
        assertEquals(ResetRule.DEFAULT, load("reset-when: everyone-dead").resetWhen());
    }

    @Test
    void spawnProtectionCannotBeNegative() throws Exception {
        assertEquals(0, load("spawn-protection-seconds: 0").spawnProtectionSeconds());
        assertEquals(0, load("spawn-protection-seconds: -5").spawnProtectionSeconds());
        assertEquals(10, load("spawn-protection-seconds: 10").spawnProtectionSeconds());
    }
}
