package io.github.doggylover314.hardcorechallenge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doggylover314.hardcorechallenge.core.ResetRule;
import io.github.doggylover314.hardcorechallenge.core.SeedList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void seedListDefaultsToOnceAndEmpty() throws Exception {
        Settings settings = load("");
        assertEquals(SeedList.Mode.ONCE, settings.seedMode());
        assertTrue(settings.seeds().isEmpty());
    }

    @Test
    void readsTheSeedListMode() throws Exception {
        assertEquals(SeedList.Mode.ONCE, load("seed-list:\n  mode: once").seedMode());
        assertEquals(SeedList.Mode.CYCLE, load("seed-list:\n  mode: cycle").seedMode());
        assertEquals(SeedList.Mode.CYCLE, load("seed-list:\n  mode: Cycle").seedMode());
    }

    @Test
    void anInvalidSeedListModeFallsBackToOnce() throws Exception {
        assertEquals(SeedList.Mode.ONCE, load("seed-list:\n  mode: forever").seedMode());
        assertEquals(SeedList.Mode.ONCE, load("seed-list:\n  mode: \"\"").seedMode());
    }

    @Test
    void readsSeedsAsText() throws Exception {
        Settings settings = load("""
                seed-list:
                  seeds:
                    - 12345
                    - -987
                    - "my text seed"
                    - "42"
                    - 123456789012345678901234567890
                """);
        assertEquals(List.of("12345", "-987", "my text seed", "42", "123456789012345678901234567890"), settings.seeds());
    }

    @Test
    void readsSeedsWrittenInline() throws Exception {
        assertEquals(List.of("1", "two"), load("seed-list:\n  seeds: [1, two]").seeds());
    }

    @Test
    void aSingleSeedWithoutAListCountsAsOne() throws Exception {
        assertEquals(List.of("12345"), load("seed-list:\n  seeds: 12345").seeds());
        assertEquals(List.of("my seed"), load("seed-list:\n  seeds: my seed").seeds());
        assertEquals(List.of("a b"), load("seed-list:\n  seeds: \"a b\"").seeds());
        assertTrue(load("seed-list:\n  seeds: \"\"").seeds().isEmpty());
    }

    @Test
    void seedsThatAreNotAListAreIgnoredWithAWarning() throws Exception {
        List<String> warnings = warningsFor("seed-list:\n  seeds:\n    a: 1");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("must be a list"), warnings.get(0));
        assertTrue(load("seed-list:\n  seeds:\n    a: 1").seeds().isEmpty());
    }

    @Test
    void blankSeedsAreDropped() throws Exception {
        assertEquals(List.of("a"), load("seed-list:\n  seeds: [\"\", \"  \", a, ~]").seeds());
        assertTrue(load("seed-list:\n  seeds:").seeds().isEmpty());
    }

    /** Reads the seed list and returns what was logged as warnings. */
    private static List<String> warningsFor(String yaml) throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        Settings.readSeeds(config, logger);
        return warnings;
    }

    @Test
    void unquotedSeedsThatYamlChangesAreWarnedAbout() throws Exception {
        // yes is a boolean, 1.5 a double, the date a date: none of them is the text that was typed.
        List<String> warnings = warningsFor("seed-list:\n  seeds: [yes, 1.5, 2024-01-01]");
        assertEquals(3, warnings.stream().filter(w -> w.contains("seed-list.seeds") && w.contains("quotes")).count(), warnings.toString());
        assertTrue(warnings.get(0).contains("'true'") && warnings.get(0).contains("Boolean"), warnings.get(0));
        assertTrue(warnings.get(1).contains("'1.5'") && warnings.get(1).contains("Double"), warnings.get(1));
        assertTrue(warnings.get(2).contains("Date"), warnings.get(2));
    }

    @Test
    void anEmptyEntryIsWarnedAbout() throws Exception {
        assertEquals(1, warningsFor("seed-list:\n  seeds: [a, ~]").size());
    }

    @Test
    void numbersAndQuotedTextAreNotWarnedAbout() throws Exception {
        assertEquals(List.of(), warningsFor("""
                seed-list:
                  seeds: [12345, -987, 123456789012345678901234567890, "yes", "010", "1.5", "my text seed", ""]
                """));
    }

    @Test
    void quotedSeedsKeepTheTextAsTyped() throws Exception {
        assertEquals(List.of("yes", "010", "1.5", "0x1F", "12:30", "2024-01-01"),
                load("seed-list:\n  seeds: [\"yes\", \"010\", \"1.5\", \"0x1F\", \"12:30\", \"2024-01-01\"]").seeds());
    }

    @Test
    void aValidFileIsReadable(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("config.yml"), "reset-when: 50%\nseed-list:\n  seeds: [1]\n");
        assertTrue(Settings.isReadable(file, LOGGER));
        assertTrue(Settings.isReadable(Files.writeString(dir.resolve("empty.yml"), ""), LOGGER));
    }

    @Test
    void aFileWithAYamlErrorIsNotReadable(@TempDir Path dir) throws Exception {
        // An unclosed quote: Bukkit would load this as an empty config.
        Path file = Files.writeString(dir.resolve("config.yml"), "reset-when: first-death\nmessages:\n  prefix: \"[HCC\n");
        assertFalse(Settings.isReadable(file, LOGGER));
    }

    @Test
    void aMissingFileIsNotReadable(@TempDir Path dir) {
        assertFalse(Settings.isReadable(dir.resolve("config.yml"), LOGGER));
    }

    private static String bundledConfig() throws Exception {
        try (var in = SettingsTest.class.getResourceAsStream("/config.yml")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void theBundledConfigHasTheSeedListDefaults() throws Exception {
        Settings settings = load(bundledConfig());
        assertEquals(SeedList.Mode.ONCE, settings.seedMode());
        assertTrue(settings.seeds().isEmpty());
    }

    @Test
    void theBundledConfigHasEverySeedListMessage() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(bundledConfig());
        for (String key : new String[] {"seeds-header", "seeds-empty", "seeds-line", "seeds-line-next", "seeds-added",
                "seeds-removed", "seeds-cleared", "seeds-remove-invalid", "seeds-add-blank", "seeds-mode-once",
                "seeds-mode-cycle", "seeds-used-up", "run-origin-list", "config-unreadable", "seeds-config-error"}) {
            assertTrue(config.isString("messages." + key), key);
        }
    }

    @Test
    void savingTheSeedListKeepsTheRestOfTheFile() throws Exception {
        // What ChallengeManager does: set the keys on the freshly loaded file and save it.
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(bundledConfig());
        config.set("seed-list.mode", "cycle");
        config.set("seed-list.seeds", new java.util.ArrayList<>(List.of("12345", "my text seed", "-5", "007")));
        String saved = config.saveToString();

        assertTrue(saved.contains("# once = play each seed one time"), "comments are kept");
        assertTrue(saved.contains("reset-when: first-death"));
        Settings settings = load(saved);
        assertEquals(SeedList.Mode.CYCLE, settings.seedMode());
        assertEquals(List.of("12345", "my text seed", "-5", "007"), settings.seeds());
    }
}
