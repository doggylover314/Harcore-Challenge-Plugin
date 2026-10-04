package io.github.doggylover314.hardcorechallenge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Renders messages from the config.yml that ships in the jar. */
class MessagesTest {
    private Messages messages;

    @BeforeEach
    void loadBundledConfig() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = MessagesTest.class.getResourceAsStream("/config.yml")) {
            config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        messages = new Messages(config, Logger.getLogger("MessagesTest"));
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void theSeedListIsShownNumberedWithTheNextOneMarked() {
        assertEquals("Seed list (mode: cycle, 3 in the list)", text(messages.plain("seeds-header",
                Placeholder.unparsed("mode", "cycle"), Placeholder.unparsed("count", "3"))));
        assertEquals("  2. 12345", text(messages.plain("seeds-line",
                Placeholder.unparsed("number", "2"), Placeholder.unparsed("seed", "12345"))));
        assertEquals("  1. my seed ← next", text(messages.plain("seeds-line-next",
                Placeholder.unparsed("number", "1"), Placeholder.unparsed("seed", "my seed"))));
    }

    @Test
    void seedsAreShownAsTypedEvenIfTheyLookLikeFormatting() {
        assertEquals("  1. <red>hi</red>", text(messages.plain("seeds-line",
                Placeholder.unparsed("number", "1"), Placeholder.unparsed("seed", "<red>hi</red>"))));
        assertEquals("Added <b>x</b> to the seed list as number 4.", text(messages.plain("seeds-added",
                Placeholder.unparsed("seed", "<b>x</b>"), Placeholder.unparsed("number", "4"))));
    }

    @Test
    void theEmptyMessageNamesTheCommandToFillTheList() {
        String empty = text(messages.plain("seeds-empty", Placeholder.unparsed("mode", "once")));
        assertEquals("The seed list is empty (mode: once), so runs use random seeds. Add one with /hcc seeds add <seed>.", empty);
    }

    @Test
    void theOtherSeedMessages() {
        assertEquals("Removed 99 (number 2) from the seed list.", text(messages.plain("seeds-removed",
                Placeholder.unparsed("seed", "99"), Placeholder.unparsed("number", "2"))));
        assertEquals("Cleared the seed list (5 removed). Runs use random seeds.",
                text(messages.plain("seeds-cleared", Placeholder.unparsed("count", "5"))));
        assertEquals("There is no seed number 7. The list has 3.", text(messages.plain("seeds-remove-invalid",
                Placeholder.unparsed("number", "7"), Placeholder.unparsed("count", "3"))));
        assertEquals("The seed list is empty. The next runs use random seeds.", text(messages.plain("seeds-used-up")));
    }

    @Test
    void theRunOriginIsAddedNextToTheSeed() {
        assertEquals(" · from seed list", text(messages.plain("run-origin-list")));
    }

    @Test
    void onlyTheAdminHelpMentionsTheSeedList() {
        assertTrue(text(messages.plain("help")).contains("/hcc seeds [add <seed> | remove <number> | clear | mode <once|cycle>]"));
        assertFalse(text(messages.plain("help-player")).contains("seeds"));
    }
}
