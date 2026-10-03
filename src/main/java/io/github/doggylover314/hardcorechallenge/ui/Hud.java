package io.github.doggylover314.hardcorechallenge.ui;

import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.config.Settings;
import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.RunStateMachine;
import io.github.doggylover314.hardcorechallenge.core.TimeFormat;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Boss checklist on a sidebar and progress on a boss bar, shown to everyone while a run is active.
 */
public final class Hud {
    private static final int MAX_LINES = 15;

    private final RunStateMachine machine;
    private final IntSupplier aliveParticipants;
    private final IntSupplier totalParticipants;
    private Settings settings;
    private Messages messages;

    private final BossBar bossBar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    private Scoreboard scoreboard;
    private Objective objective;
    private boolean visible;

    public Hud(RunStateMachine machine, IntSupplier aliveParticipants, IntSupplier totalParticipants, Settings settings, Messages messages) {
        this.machine = machine;
        this.aliveParticipants = aliveParticipants;
        this.totalParticipants = totalParticipants;
        this.settings = settings;
        this.messages = messages;
    }

    public void reload(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
        if (visible) {
            if (!settings.announceBossbar()) {
                Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(bossBar));
            }
            rebuildScoreboard();
            Bukkit.getOnlinePlayers().forEach(this::show);
        }
        update();
    }

    /** Shows the HUD to everyone online and keeps it up to date until {@link #hide()}. */
    public void showAll() {
        visible = true;
        if (scoreboard == null) {
            rebuildScoreboard();
        }
        update();
        Bukkit.getOnlinePlayers().forEach(this::show);
    }

    public void show(Player player) {
        if (!visible) {
            return;
        }
        if (settings.announceBossbar()) {
            player.showBossBar(bossBar);
        }
        if (scoreboard != null) {
            player.setScoreboard(scoreboard);
        }
    }

    public void hide() {
        visible = false;
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bossBar);
            if (scoreboard != null && player.getScoreboard().equals(scoreboard)) {
                player.setScoreboard(main);
            }
        }
    }

    public boolean visible() {
        return visible;
    }

    private void rebuildScoreboard() {
        Scoreboard fresh = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = fresh.registerNewObjective("hcc", Criteria.DUMMY, messages.plain("sidebar-title"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());
        Scoreboard old = scoreboard;
        scoreboard = fresh;
        objective = obj;
        if (old != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getScoreboard().equals(old)) {
                    player.setScoreboard(fresh);
                }
            }
        }
    }

    /** Refreshes text and progress. Called every second and after every state change. */
    public void update() {
        if (!visible || objective == null) {
            return;
        }
        Set<Boss> tracked = machine.trackedBosses();
        int total = tracked.size();
        int killed = (int) tracked.stream().filter(machine::hasKilled).count();
        String time = TimeFormat.clock(machine.elapsedMillis());
        String run = String.valueOf(machine.runNumber());

        bossBar.name(messages.plain("bossbar",
                Placeholder.unparsed("count", String.valueOf(killed)),
                Placeholder.unparsed("total", String.valueOf(total)),
                Placeholder.unparsed("run", run),
                Placeholder.unparsed("time", time)));
        bossBar.progress(total == 0 ? 0f : Math.min(1f, (float) killed / total));
        bossBar.color(machine.phase() == RunPhase.VICTORY ? BossBar.Color.YELLOW : BossBar.Color.RED);

        List<Component> lines = new ArrayList<>();
        lines.add(messages.plain("sidebar-run", Placeholder.unparsed("run", run)));
        lines.add(messages.plain("sidebar-time", Placeholder.unparsed("time", time)));
        if (machine.phase() == RunPhase.RUNNING && !machine.clockRunning()) {
            lines.add(messages.plain("sidebar-phase-paused"));
        } else if (machine.phase() == RunPhase.RESETTING) {
            lines.add(messages.plain("sidebar-phase-resetting"));
        } else if (machine.phase() == RunPhase.VICTORY) {
            lines.add(messages.plain("sidebar-phase-victory"));
        }
        lines.add(Component.empty());
        for (Boss boss : tracked) {
            String key = machine.hasKilled(boss) ? "sidebar-boss-done" : "sidebar-boss-todo";
            lines.add(messages.plain(key, Placeholder.unparsed("boss", boss.displayName())));
        }
        lines.add(Component.empty());
        lines.add(messages.plain("sidebar-players",
                Placeholder.unparsed("alive", String.valueOf(aliveParticipants.getAsInt())),
                Placeholder.unparsed("total", String.valueOf(totalParticipants.getAsInt()))));
        setLines(lines);
    }

    private void setLines(List<Component> lines) {
        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = "line" + i;
            if (i < count) {
                Score score = objective.getScore(entry);
                score.setScore(count - i);
                score.customName(lines.get(i));
            } else if (scoreboard.getEntries().contains(entry)) {
                scoreboard.resetScores(entry);
            }
        }
    }
}
