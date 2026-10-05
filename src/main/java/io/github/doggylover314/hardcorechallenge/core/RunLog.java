package io.github.doggylover314.hardcorechallenge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything recorded about one run: who played, what happened when, and how it ended.
 * A run that is still being played has no outcome yet.
 */
public final class RunLog {
    private final int runNumber;
    private final long seed;
    private final String worldName;
    private final long startedAt;
    private final Integer replayOf;
    private final boolean customSeed;
    private final boolean seedList;

    private final Map<UUID, String> participants = new LinkedHashMap<>();
    private final List<TimelineEvent> timeline = new ArrayList<>();
    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private final List<BossKill> bossKills = new ArrayList<>();
    private final Map<Boss, String> finalHits = new EnumMap<>(Boss.class);
    private final Set<String> firsts = new LinkedHashSet<>();

    private Outcome outcome;
    private long endedAt;
    private long durationMillis;
    private DeathRecord death;
    private String reason;
    /** Run time and wall-clock time of the last save of a live log; lets a crashed run be closed with a real duration. */
    private long checkpointMillis;
    private long checkpointAt;

    /**
     * @param replayOf   run whose seed this run reuses, or {@code null}
     * @param customSeed whether an admin picked the seed
     * @param seedList   whether the seed came from the admin's seed list
     */
    public RunLog(int runNumber, long seed, String worldName, long startedAt, Integer replayOf, boolean customSeed, boolean seedList) {
        this.runNumber = runNumber;
        this.seed = seed;
        this.worldName = worldName;
        this.startedAt = startedAt;
        this.replayOf = replayOf;
        this.customSeed = customSeed;
        this.seedList = seedList;
    }

    public RunLog(int runNumber, long seed, String worldName, long startedAt, Integer replayOf, boolean customSeed) {
        this(runNumber, seed, worldName, startedAt, replayOf, customSeed, false);
    }

    // ------------------------------------------------------------------ recording

    public void addParticipant(UUID id, String name) {
        participants.put(id, name);
        stats(id, name);
    }

    public PlayerStats stats(UUID id, String name) {
        PlayerStats playerStats = stats.computeIfAbsent(id, key -> new PlayerStats(name));
        if (name != null) {
            playerStats.name(name);
        }
        return playerStats;
    }

    public void event(TimelineEvent event) {
        timeline.add(event);
    }

    /**
     * Marks something as having happened for the first time in this run.
     *
     * @return true if this is the first time
     */
    public boolean first(String key) {
        return firsts.add(key);
    }

    public boolean hasFirst(String key) {
        return firsts.contains(key);
    }

    /** Records a boss kill. {@code finalHit} is the player who landed the killing blow, or {@code null}. */
    public void bossKilled(Boss boss, long elapsedMillis, UUID finalHitId, String finalHit) {
        bossKills.add(new BossKill(boss, elapsedMillis));
        if (finalHit != null) {
            finalHits.put(boss, finalHit);
            if (finalHitId != null) {
                stats(finalHitId, finalHit).addBossKill();
            }
        }
    }

    public void finish(Outcome outcome, long endedAt, long durationMillis, DeathRecord death, String reason) {
        this.outcome = outcome;
        this.endedAt = endedAt;
        this.durationMillis = durationMillis;
        this.death = death;
        this.reason = reason;
    }

    /**
     * Makes a finished log live again, for a run that goes on after it ended: drops the RUN_ENDED event and
     * clears how it ended. Every other event stays.
     *
     * @return false if the log was already live
     */
    public boolean reopen() {
        if (isLive()) {
            return false;
        }
        for (int i = timeline.size() - 1; i >= 0; i--) {
            if (timeline.get(i).type() == TimelineEvent.Type.RUN_ENDED) {
                timeline.remove(i);
                break;
            }
        }
        outcome = null;
        endedAt = 0L;
        durationMillis = 0L;
        death = null;
        reason = null;
        return true;
    }

    /** Notes how far the run had got when the live log was last saved. */
    public void checkpoint(long elapsedMillis, long at) {
        this.checkpointMillis = elapsedMillis;
        this.checkpointAt = at;
    }

    /** Used when loading saved runs. */
    public void restoreBossKill(BossKill kill, String finalHit) {
        bossKills.add(kill);
        if (finalHit != null) {
            finalHits.put(kill.boss(), finalHit);
        }
    }

    /** Used when loading saved runs. */
    public void restoreFirst(String key) {
        firsts.add(key);
    }

    /** Used when loading saved runs. */
    public void restoreStats(UUID id, PlayerStats playerStats) {
        stats.put(id, playerStats);
    }

    // ------------------------------------------------------------------ queries

    public boolean isLive() {
        return outcome == null;
    }

    /**
     * Each player's share of all damage dealt to bosses during the run, from 0 to 1.
     * Empty if nobody damaged a boss.
     */
    public Map<UUID, Double> bossDamageShare() {
        double total = 0;
        for (PlayerStats playerStats : stats.values()) {
            total += playerStats.bossDamage();
        }
        Map<UUID, Double> share = new LinkedHashMap<>();
        if (total <= 0) {
            return share;
        }
        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            share.put(entry.getKey(), entry.getValue().bossDamage() / total);
        }
        return share;
    }

    /**
     * Best guess at how long a live log ran, for closing it after a crash: the latest of the last
     * checkpoint and the last timeline event.
     */
    public long recoveredDurationMillis() {
        long millis = checkpointMillis;
        for (TimelineEvent event : timeline) {
            millis = Math.max(millis, event.elapsedMillis());
        }
        return millis;
    }

    /** Best guess at when a live log stopped being written, for closing it after a crash. */
    public long recoveredEndedAt() {
        long at = Math.max(startedAt, checkpointAt);
        for (TimelineEvent event : timeline) {
            at = Math.max(at, event.at());
        }
        return at;
    }

    /** Whether the player (by name, case-insensitive) took part in this run. */
    public boolean involves(String playerName) {
        String wanted = playerName.toLowerCase(Locale.ROOT);
        for (String name : participants.values()) {
            if (name.toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    public int runNumber() {
        return runNumber;
    }

    public long seed() {
        return seed;
    }

    public String worldName() {
        return worldName;
    }

    public long startedAt() {
        return startedAt;
    }

    public Integer replayOf() {
        return replayOf;
    }

    public boolean customSeed() {
        return customSeed;
    }

    public boolean seedList() {
        return seedList;
    }

    public Map<UUID, String> participants() {
        return Collections.unmodifiableMap(participants);
    }

    public List<TimelineEvent> timeline() {
        return Collections.unmodifiableList(timeline);
    }

    public Map<UUID, PlayerStats> stats() {
        return Collections.unmodifiableMap(stats);
    }

    public List<BossKill> bossKills() {
        return Collections.unmodifiableList(bossKills);
    }

    public String finalHit(Boss boss) {
        return finalHits.get(boss);
    }

    public Set<String> firsts() {
        return Collections.unmodifiableSet(firsts);
    }

    public long checkpointMillis() {
        return checkpointMillis;
    }

    public long checkpointAt() {
        return checkpointAt;
    }

    public Outcome outcome() {
        return outcome;
    }

    public long endedAt() {
        return endedAt;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public DeathRecord death() {
        return death;
    }

    public String reason() {
        return reason;
    }
}
