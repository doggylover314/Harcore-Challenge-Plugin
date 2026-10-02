package io.github.doggylover314.hardcorechallenge.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The run lifecycle, free of any Bukkit types so it can be unit tested.
 *
 * <pre>
 *   IDLE ──beginTransition──▶ RESETTING ──beginRun──▶ RUNNING
 *                                ▲                      │ │
 *                                │   death (next tick)  │ │ last boss
 *                                ├──────────────────────┘ ▼
 *                                └──────beginTransition── VICTORY
 *   any ──stop──▶ IDLE
 * </pre>
 *
 * <p>Deaths do not end the run immediately. They are held as pending and only resolve into a
 * defeat on a later tick, so a victory that lands in the same tick as a death wins.</p>
 */
public final class RunStateMachine {

    public enum DeathResult {
        /** The death was recorded and will end the run when resolved on a later tick. */
        PENDING,
        /** Another death is already pending this tick; this one does not change the outcome. */
        ALREADY_PENDING,
        /** No run is being played, so the death does not matter. */
        IGNORED
    }

    public enum BossResult {
        /** A new boss was checked off. */
        RECORDED,
        /** A new boss was checked off and it completed the run. */
        VICTORY,
        /** This boss was already killed during the run. */
        DUPLICATE,
        /** This boss is not in the configured objective list. */
        NOT_TRACKED,
        /** No run is being played (or the run has already been lost). */
        IGNORED
    }

    private final RunClock clock;
    private final LongSupplier wallClock;

    private RunPhase phase = RunPhase.IDLE;
    private int runNumber;
    private String worldName;
    private long seed;
    private long startedAt;
    private final List<BossKill> bossKills = new ArrayList<>();

    private Set<Boss> trackedBosses = EnumSet.allOf(Boss.class);
    private boolean requireAllBosses = true;
    private boolean victoryEnabled = true;

    private DeathRecord pendingDeath;
    private long pendingTick;

    /**
     * @param runMillis monotonic millisecond source used for run time
     * @param wallClock epoch millisecond source used for start / end timestamps
     */
    public RunStateMachine(LongSupplier runMillis, LongSupplier wallClock) {
        this.clock = new RunClock(runMillis);
        this.wallClock = wallClock;
    }

    /**
     * Sets which bosses count and whether all of them (or any one) are needed for victory.
     * With victory disabled, kills are still checked off but never end the run.
     */
    public void configure(Collection<Boss> tracked, boolean requireAll, boolean victoryEnabled) {
        this.trackedBosses = tracked.isEmpty() ? EnumSet.noneOf(Boss.class) : EnumSet.copyOf(tracked);
        this.requireAllBosses = requireAll;
        this.victoryEnabled = victoryEnabled;
    }

    // ---------------------------------------------------------------- transitions

    /**
     * Ends whatever is happening and enters {@link RunPhase#RESETTING}, where the countdown runs
     * and the next world is created.
     *
     * @return false if a transition is already in progress
     */
    public boolean beginTransition() {
        if (phase == RunPhase.RESETTING) {
            return false;
        }
        clock.stop();
        pendingDeath = null;
        phase = RunPhase.RESETTING;
        return true;
    }

    /**
     * Starts the next run once its world exists. Only legal while resetting.
     */
    public void beginRun(int newRunNumber, String newWorldName, long newSeed) {
        if (phase != RunPhase.RESETTING) {
            throw new IllegalStateException("Cannot begin a run from " + phase);
        }
        if (newRunNumber <= runNumber) {
            throw new IllegalArgumentException("Run number must increase: " + newRunNumber + " <= " + runNumber);
        }
        runNumber = newRunNumber;
        worldName = newWorldName;
        seed = newSeed;
        startedAt = wallClock.getAsLong();
        bossKills.clear();
        pendingDeath = null;
        clock.reset(0);
        clock.start();
        phase = RunPhase.RUNNING;
    }

    /**
     * Records a death that should end the run. The run is not lost until
     * {@link #resolvePendingDeath(long)} is called on a later tick.
     */
    public DeathResult reportDeath(DeathRecord death, long tick) {
        if (phase != RunPhase.RUNNING) {
            return DeathResult.IGNORED;
        }
        if (pendingDeath != null) {
            return DeathResult.ALREADY_PENDING;
        }
        pendingDeath = death;
        pendingTick = tick;
        return DeathResult.PENDING;
    }

    /**
     * Turns a pending death into a defeat if its tick has passed and nothing overrode it.
     *
     * @return the death that ended the run, if the run was lost now
     */
    public Optional<DeathRecord> resolvePendingDeath(long tick) {
        if (phase != RunPhase.RUNNING || pendingDeath == null || tick <= pendingTick) {
            return Optional.empty();
        }
        DeathRecord death = pendingDeath;
        pendingDeath = null;
        clock.stop();
        phase = RunPhase.RESETTING;
        return Optional.of(death);
    }

    /**
     * Credits a boss kill to the current run.
     */
    public BossResult reportBossKill(Boss boss, long tick) {
        if (phase != RunPhase.RUNNING) {
            return BossResult.IGNORED;
        }
        if (pendingDeath != null && tick > pendingTick) {
            // The death happened on an earlier tick; it stands and this kill is too late.
            return BossResult.IGNORED;
        }
        if (!trackedBosses.contains(boss)) {
            return BossResult.NOT_TRACKED;
        }
        if (hasKilled(boss)) {
            return BossResult.DUPLICATE;
        }
        bossKills.add(new BossKill(boss, clock.elapsedMillis()));
        if (victoryConditionMet()) {
            enterVictory();
            return BossResult.VICTORY;
        }
        return BossResult.RECORDED;
    }

    /**
     * Re-checks the victory condition, e.g. after the boss list was changed by a config reload.
     *
     * @return true if the run is now won
     */
    public boolean reevaluateVictory() {
        if (phase == RunPhase.RUNNING && victoryConditionMet()) {
            enterVictory();
            return true;
        }
        return false;
    }

    /**
     * Stops the run clock without ending the run, e.g. while no participant is online.
     * Has no effect unless a run is being played.
     */
    public void pauseClock() {
        if (phase == RunPhase.RUNNING) {
            clock.stop();
        }
    }

    /** Restarts the run clock after {@link #pauseClock()}. */
    public void resumeClock() {
        if (phase == RunPhase.RUNNING) {
            clock.start();
        }
    }

    public boolean clockRunning() {
        return clock.running();
    }

    /** Ends the run and goes idle. The world name is kept so the world can be cleaned up later. */
    public RunPhase stop() {
        RunPhase previous = phase;
        clock.stop();
        pendingDeath = null;
        phase = RunPhase.IDLE;
        return previous;
    }

    private void enterVictory() {
        clock.stop();
        pendingDeath = null;
        phase = RunPhase.VICTORY;
    }

    private boolean victoryConditionMet() {
        if (!victoryEnabled || trackedBosses.isEmpty()) {
            return false;
        }
        if (requireAllBosses) {
            return trackedBosses.stream().allMatch(this::hasKilled);
        }
        return bossKills.stream().anyMatch(kill -> trackedBosses.contains(kill.boss()));
    }

    // ---------------------------------------------------------------- queries

    public RunPhase phase() {
        return phase;
    }

    public int runNumber() {
        return runNumber;
    }

    public String worldName() {
        return worldName;
    }

    public long seed() {
        return seed;
    }

    public long startedAt() {
        return startedAt;
    }

    public long elapsedMillis() {
        return clock.elapsedMillis();
    }

    public boolean hasKilled(Boss boss) {
        for (BossKill kill : bossKills) {
            if (kill.boss() == boss) {
                return true;
            }
        }
        return false;
    }

    public List<BossKill> bossKills() {
        return List.copyOf(bossKills);
    }

    public Set<Boss> trackedBosses() {
        return trackedBosses.isEmpty() ? EnumSet.noneOf(Boss.class) : EnumSet.copyOf(trackedBosses);
    }

    public boolean requireAllBosses() {
        return requireAllBosses;
    }

    public boolean hasPendingDeath() {
        return pendingDeath != null;
    }

    /** Whether a run is in play or has just ended and still has its world, i.e. not idle. */
    public boolean isActive() {
        return phase != RunPhase.IDLE;
    }

    // ---------------------------------------------------------------- persistence

    public RunSnapshot snapshot() {
        return new RunSnapshot(phase, runNumber, worldName, seed, startedAt, clock.elapsedMillis(), bossKills);
    }

    /**
     * Restores persisted state. A run that was being played resumes its clock; time the server
     * spent offline is not counted.
     */
    public void restore(RunSnapshot snapshot) {
        phase = snapshot.phase();
        runNumber = snapshot.runNumber();
        worldName = snapshot.worldName();
        seed = snapshot.seed();
        startedAt = snapshot.startedAt();
        bossKills.clear();
        bossKills.addAll(snapshot.bossKills());
        pendingDeath = null;
        clock.reset(snapshot.elapsedMillis());
        if (phase == RunPhase.RUNNING) {
            clock.start();
        }
    }
}
