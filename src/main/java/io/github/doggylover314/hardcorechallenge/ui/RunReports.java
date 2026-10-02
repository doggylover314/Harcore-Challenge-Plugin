package io.github.doggylover314.hardcorechallenge.ui;

import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.BossKill;
import io.github.doggylover314.hardcorechallenge.core.DeathRecord;
import io.github.doggylover314.hardcorechallenge.core.Outcome;
import io.github.doggylover314.hardcorechallenge.core.PlayerStats;
import io.github.doggylover314.hardcorechallenge.core.RunLog;
import io.github.doggylover314.hardcorechallenge.core.RunQuery;
import io.github.doggylover314.hardcorechallenge.core.TimeFormat;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/**
 * Chat views of the runs list: paged list, run details and timeline.
 */
public final class RunReports {
    public static final int RUNS_PER_PAGE = 10;
    public static final int EVENTS_PER_PAGE = 15;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Supplier<Messages> messages;
    /** Live run time for runs still being played. */
    private final Function<RunLog, Long> liveElapsed;

    public RunReports(Supplier<Messages> messages, Function<RunLog, Long> liveElapsed) {
        this.messages = messages;
        this.liveElapsed = liveElapsed;
    }

    // ---------------------------------------------------------------------- list

    /**
     * @param baseCommand command that shows this list, without the page number (for prev/next)
     */
    public void sendList(CommandSender sender, List<RunLog> runs, int page, String baseCommand, String filterLabel) {
        Messages m = messages.get();
        if (runs.isEmpty()) {
            sender.sendMessage(m.chat("runs-empty"));
            return;
        }
        RunQuery.Page<RunLog> shown = RunQuery.page(runs, page, RUNS_PER_PAGE);
        sender.sendMessage(m.chat("runs-header",
                Placeholder.unparsed("count", String.valueOf(shown.total())),
                Placeholder.unparsed("filter", filterLabel)));
        for (RunLog run : shown.items()) {
            Component line = m.plain("runs-line",
                    Placeholder.unparsed("run", String.valueOf(run.runNumber())),
                    Placeholder.component("outcome", outcome(run)),
                    Placeholder.unparsed("time", TimeFormat.clock(duration(run))),
                    Placeholder.unparsed("bosses", String.valueOf(run.bossKills().size())),
                    Placeholder.unparsed("players", String.join(", ", run.participants().values())),
                    Placeholder.unparsed("date", DATE.format(Instant.ofEpochMilli(run.startedAt()))));
            sender.sendMessage(line
                    .clickEvent(ClickEvent.runCommand("/hcc run " + run.runNumber()))
                    .hoverEvent(HoverEvent.showText(m.plain("runs-line-hover", Placeholder.unparsed("run", String.valueOf(run.runNumber()))))));
        }
        sender.sendMessage(navigation(shown.page(), shown.pages(), baseCommand));
    }

    // -------------------------------------------------------------------- detail

    public void sendDetail(CommandSender sender, RunLog run) {
        Messages m = messages.get();
        String number = String.valueOf(run.runNumber());
        sender.sendMessage(m.chat("run-header",
                Placeholder.unparsed("run", number),
                Placeholder.component("outcome", outcome(run)),
                Placeholder.unparsed("time", TimeFormat.clock(duration(run))),
                Placeholder.unparsed("date", DATE.format(Instant.ofEpochMilli(run.startedAt())))));

        String origin = "";
        if (run.replayOf() != null) {
            origin = m.raw("run-origin-replay").replace("<of>", String.valueOf(run.replayOf()));
        } else if (run.customSeed()) {
            origin = m.raw("run-origin-custom");
        }
        sender.sendMessage(m.plain("run-seed",
                        Placeholder.unparsed("seed", String.valueOf(run.seed())),
                        Placeholder.parsed("origin", origin))
                .clickEvent(ClickEvent.copyToClipboard(String.valueOf(run.seed())))
                .hoverEvent(HoverEvent.showText(m.plain("run-seed-hover"))));

        sender.sendMessage(m.plain("run-players",
                Placeholder.unparsed("players", run.participants().isEmpty() ? m.raw("none") : String.join(", ", run.participants().values()))));

        List<Component> bosses = new ArrayList<>();
        for (BossKill kill : run.bossKills()) {
            String finalHit = run.finalHit(kill.boss());
            bosses.add(m.plain("run-boss",
                    Placeholder.unparsed("boss", kill.boss().displayName()),
                    Placeholder.unparsed("time", TimeFormat.clock(kill.elapsedMillis())),
                    Placeholder.unparsed("player", finalHit == null ? "?" : finalHit)));
        }
        sender.sendMessage(m.plain("run-bosses",
                Placeholder.component("bosses", bosses.isEmpty() ? m.plain("none") : Component.join(JoinConfiguration.separator(Component.text(" → ")), bosses))));

        DeathRecord death = run.death();
        if (death != null) {
            sender.sendMessage(m.plain("run-death",
                    Placeholder.unparsed("player", death.playerName()),
                    Placeholder.unparsed("cause", death.cause()),
                    Placeholder.unparsed("killer", death.killer() == null ? "" : " (" + death.killer() + ")"),
                    Placeholder.unparsed("x", String.valueOf(death.x())),
                    Placeholder.unparsed("y", String.valueOf(death.y())),
                    Placeholder.unparsed("z", String.valueOf(death.z())),
                    Placeholder.unparsed("world", death.world() == null ? "?" : death.world())));
        }
        if (run.reason() != null) {
            sender.sendMessage(m.plain("run-reason", Placeholder.unparsed("reason", run.reason())));
        }

        if (!run.stats().isEmpty()) {
            sender.sendMessage(m.plain("run-stats-header"));
            Map<UUID, Double> share = run.bossDamageShare();
            for (Map.Entry<UUID, PlayerStats> entry : run.stats().entrySet()) {
                PlayerStats s = entry.getValue();
                double pct = share.getOrDefault(entry.getKey(), 0.0) * 100;
                sender.sendMessage(m.plain("run-stats-line",
                        Placeholder.unparsed("player", s.name()),
                        Placeholder.unparsed("share", String.format(Locale.ROOT, "%.0f%%", pct)),
                        Placeholder.unparsed("boss_kills", String.valueOf(s.bossKills())),
                        Placeholder.unparsed("mobs", String.valueOf(s.mobsKilled())),
                        Placeholder.unparsed("damage", String.format(Locale.ROOT, "%.0f", s.damageDealt())),
                        Placeholder.unparsed("distance", distance(s.distanceCm())),
                        Placeholder.unparsed("played", TimeFormat.clock(s.timePlayedMillis()))));
            }
        }

        Component buttons = Component.join(JoinConfiguration.separator(Component.space()),
                m.plain("run-button-timeline").clickEvent(ClickEvent.runCommand("/hcc run " + number + " timeline")),
                m.plain("run-button-replay").clickEvent(ClickEvent.suggestCommand("/hcc start replay " + number)),
                m.plain("run-button-delete").clickEvent(ClickEvent.runCommand("/hcc run " + number + " delete")),
                m.plain("run-button-back").clickEvent(ClickEvent.runCommand("/hcc runs")));
        sender.sendMessage(buttons);
    }

    // ------------------------------------------------------------------ timeline

    public void sendTimeline(CommandSender sender, RunLog run, int page) {
        Messages m = messages.get();
        List<TimelineEvent> events = run.timeline();
        RunQuery.Page<TimelineEvent> shown = RunQuery.page(events, page, EVENTS_PER_PAGE);
        sender.sendMessage(m.chat("timeline-header",
                Placeholder.unparsed("run", String.valueOf(run.runNumber())),
                Placeholder.unparsed("count", String.valueOf(events.size()))));
        for (TimelineEvent event : shown.items()) {
            sender.sendMessage(m.plain("timeline-line",
                    Placeholder.unparsed("time", TimeFormat.clock(event.elapsedMillis())),
                    Placeholder.component("event", describe(event))));
        }
        sender.sendMessage(navigation(shown.page(), shown.pages(), "/hcc run " + run.runNumber() + " timeline"));
    }

    /** One timeline entry as text, e.g. "Steve entered the Nether first". */
    public Component describe(TimelineEvent event) {
        Messages m = messages.get();
        String key = "event-" + event.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
        return m.plain(key,
                Placeholder.unparsed("player", event.player() == null ? "?" : event.player()),
                Placeholder.unparsed("detail", detailName(event.detail())));
    }

    /** Friendly name for a timeline detail key (boss, dimension, item, structure, outcome). */
    public static String detailName(String detail) {
        if (detail == null) {
            return "";
        }
        var boss = Boss.fromId(detail);
        if (boss.isPresent()) {
            return boss.get().displayName();
        }
        return switch (detail) {
            case "the_nether" -> "the Nether";
            case "the_end" -> "the End";
            case "iron_ingot" -> "iron";
            case "diamond" -> "diamonds";
            case "blaze_rod" -> "a blaze rod";
            case "ender_eye" -> "an Eye of Ender";
            case "stronghold" -> "a stronghold";
            case "monument" -> "an ocean monument";
            case "ancient_city" -> "an ancient city";
            default -> detail.replace('_', ' ');
        };
    }

    // ------------------------------------------------------------------- helpers

    private Component navigation(int page, int pages, String baseCommand) {
        Messages m = messages.get();
        Component prev = page > 1
                ? m.plain("page-prev").clickEvent(ClickEvent.runCommand(baseCommand + " " + (page - 1)))
                : m.plain("page-prev-disabled");
        Component next = page < pages
                ? m.plain("page-next").clickEvent(ClickEvent.runCommand(baseCommand + " " + (page + 1)))
                : m.plain("page-next-disabled");
        return Component.join(JoinConfiguration.separator(Component.space()), prev,
                m.plain("page-info", Placeholder.unparsed("page", String.valueOf(page)), Placeholder.unparsed("pages", String.valueOf(pages))),
                next);
    }

    private Component outcome(RunLog run) {
        Outcome outcome = run.outcome();
        String key = outcome == null ? "outcome-live" : "outcome-" + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return messages.get().plain(key);
    }

    private long duration(RunLog run) {
        return run.isLive() ? liveElapsed.apply(run) : run.durationMillis();
    }

    static String distance(long cm) {
        double metres = cm / 100.0;
        if (metres >= 1000) {
            return String.format(Locale.ROOT, "%.1f km", metres / 1000);
        }
        return String.format(Locale.ROOT, "%.0f m", metres);
    }
}
