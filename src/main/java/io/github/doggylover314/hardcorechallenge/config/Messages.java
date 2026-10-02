package io.github.doggylover314.hardcorechallenge.config;

import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage templates from the {@code messages} section of config.yml.
 */
public final class Messages {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ConfigurationSection section;
    private final Logger logger;
    private final Component prefix;

    public Messages(FileConfiguration config, Logger logger) {
        ConfigurationSection configured = config.getConfigurationSection("messages");
        this.section = configured != null ? configured : config.createSection("messages");
        this.logger = logger;
        this.prefix = parse(raw("prefix"), TagResolver.empty());
    }

    public String raw(String key) {
        String value = section.getString(key);
        if (value == null) {
            // Falls back to the bundled default through the configuration defaults, so this means
            // the key is missing entirely.
            logger.warning("Missing message 'messages." + key + "' in config.yml");
            return key;
        }
        return value;
    }

    /** Renders a message without the chat prefix (titles, sidebar, bossbar). */
    public Component plain(String key, TagResolver... placeholders) {
        return parse(raw(key), TagResolver.resolver(placeholders));
    }

    /** Renders a chat message with the configured prefix. */
    public Component chat(String key, TagResolver... placeholders) {
        return prefix.append(plain(key, placeholders));
    }

    /** Whether the message is configured as empty, meaning "don't send". */
    public boolean isBlank(String key) {
        String value = section.getString(key);
        return value != null && value.isBlank();
    }

    private Component parse(String template, TagResolver resolver) {
        try {
            return MINI.deserialize(template, resolver);
        } catch (RuntimeException e) {
            logger.warning("Could not parse MiniMessage '" + template + "': " + e.getMessage());
            return Component.text(template);
        }
    }
}
