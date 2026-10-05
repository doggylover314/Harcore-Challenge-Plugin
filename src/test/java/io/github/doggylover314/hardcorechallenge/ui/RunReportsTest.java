package io.github.doggylover314.hardcorechallenge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.core.DeathRecord;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Timeline lines rendered with the config.yml that ships in the jar. */
class RunReportsTest {
    private RunReports reports;

    @BeforeEach
    void load() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = RunReportsTest.class.getResourceAsStream("/config.yml")) {
            config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Messages messages = new Messages(config, Logger.getLogger("RunReportsTest"));
        reports = new RunReports(() -> messages, run -> 0L);
    }

    private String line(TimelineEvent event, DeathRecord death) {
        return PlainTextComponentSerializer.plainText().serialize(reports.describe(event, death));
    }

    private static DeathRecord death(String player, String cause, String message) {
        return new DeathRecord(UUID.randomUUID(), player, cause, null, message, "world", 0, 64, 0);
    }

    private static TimelineEvent event(TimelineEvent.Type type, String player, String detail) {
        return new TimelineEvent(1000, 0, type, player, detail);
    }

    @Test
    void eachDeathShowsItsOwnMessage() {
        DeathRecord fatal = death("Alex", "entity_attack", "Alex was slain by Zombie");
        assertEquals("Steve fell from a high place", line(event(TimelineEvent.Type.DEATH, "Steve", "Steve fell from a high place"), fatal));
        assertEquals("Steve fell from a high place", line(event(TimelineEvent.Type.DEATH, "Steve", "Steve fell from a high place"), null));
        assertEquals("Alex was slain by Zombie", line(event(TimelineEvent.Type.DEATH, "Alex", "Alex was slain by Zombie"), fatal));
    }

    @Test
    void oldDeathEntriesWithOnlyTheCauseUseTheRunsRecord() {
        DeathRecord fatal = death("Alex", "fall", "Alex hit the ground too hard");
        assertEquals("Alex hit the ground too hard", line(event(TimelineEvent.Type.DEATH, "Alex", "fall"), fatal));
        assertEquals("fall", line(event(TimelineEvent.Type.DEATH, "Steve", "fall"), fatal));
    }

    @Test
    void namesWithUnderscoresStayAsTheyAre() {
        assertEquals("Cool_Guy is out (Cool_Guy was slain by Zombie_Slayer)",
                line(event(TimelineEvent.Type.ELIMINATED, "Cool_Guy", "Cool_Guy was slain by Zombie_Slayer"), null));
    }

    @Test
    void otherDetailsStillGetFriendlyNames() {
        assertEquals("Steve entered the Nether first", line(event(TimelineEvent.Type.DIMENSION_FIRST, "Steve", "the_nether"), null));
    }
}
