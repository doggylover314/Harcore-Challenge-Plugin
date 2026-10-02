package io.github.doggylover314.hardcorechallenge.ui;

import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.config.Settings;
import java.time.Duration;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;

/**
 * Sends chat, titles and sounds to everyone on this server, honouring the {@code announce} toggles.
 */
public final class Announcer {
    private final Logger logger;
    private Settings settings;
    private Messages messages;

    public Announcer(Settings settings, Messages messages, Logger logger) {
        this.settings = settings;
        this.messages = messages;
        this.logger = logger;
    }

    public void reload(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    private static Audience everyone() {
        // The server audience includes the console, so announcements are logged too.
        return Bukkit.getServer();
    }

    public void chat(String key, TagResolver... placeholders) {
        if (settings.announceChat() && !messages.isBlank(key)) {
            everyone().sendMessage(messages.chat(key, placeholders));
        }
    }

    /** Chat that is always shown, regardless of {@code announce.chat} (e.g. victory summaries). */
    public void chatAlways(String key, TagResolver... placeholders) {
        if (!messages.isBlank(key)) {
            everyone().sendMessage(messages.chat(key, placeholders));
        }
    }

    public void title(String titleKey, String subtitleKey, Duration stay, TagResolver... placeholders) {
        if (!settings.announceTitle()) {
            return;
        }
        Component title = messages.plain(titleKey, placeholders);
        Component subtitle = subtitleKey == null ? Component.empty() : messages.plain(subtitleKey, placeholders);
        Title.Times times = Title.Times.times(Duration.ofMillis(250), stay, Duration.ofMillis(500));
        Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(Title.title(title, subtitle, times)));
    }

    /** A short title with no fade, for countdown ticks. */
    public void countdownTitle(TagResolver... placeholders) {
        if (!settings.announceTitle()) {
            return;
        }
        Component title = messages.plain("countdown-title", placeholders);
        Component subtitle = messages.plain("countdown-subtitle", placeholders);
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1_100), Duration.ofMillis(200));
        Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(Title.title(title, subtitle, times)));
    }

    public boolean titlesEnabled() {
        return settings.announceTitle();
    }

    public void sound(String soundKey) {
        sound(soundKey, 1f);
    }

    public void sound(String soundKey, float pitch) {
        if (!settings.announceSound()) {
            return;
        }
        String raw = settings.sound(soundKey);
        if (raw == null || raw.isBlank()) {
            return;
        }
        Key key;
        try {
            key = Key.key(raw.trim());
        } catch (InvalidKeyException e) {
            logger.warning("Invalid sound key '" + raw + "' at sounds." + soundKey);
            return;
        }
        Sound sound = Sound.sound(key, Sound.Source.MASTER, 1f, pitch);
        // Played at each listener so everyone hears it wherever they are.
        Bukkit.getOnlinePlayers().forEach(player -> player.playSound(sound, Sound.Emitter.self()));
    }
}
