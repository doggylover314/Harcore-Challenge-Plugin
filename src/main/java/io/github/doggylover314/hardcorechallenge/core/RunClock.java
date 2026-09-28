package io.github.doggylover314.hardcorechallenge.core;

import java.util.function.LongSupplier;

/**
 * Measures in-game run time. Time only accrues while the clock is running, so server downtime
 * between a shutdown and a resume is not counted against the run.
 */
public final class RunClock {
    private final LongSupplier millis;
    private long accumulated;
    private long segmentStart;
    private boolean running;

    public RunClock(LongSupplier millis) {
        this.millis = millis;
    }

    public void reset(long accumulatedMillis) {
        this.accumulated = Math.max(0, accumulatedMillis);
        this.running = false;
    }

    public void start() {
        if (!running) {
            segmentStart = millis.getAsLong();
            running = true;
        }
    }

    public void stop() {
        if (running) {
            accumulated += Math.max(0, millis.getAsLong() - segmentStart);
            running = false;
        }
    }

    public boolean running() {
        return running;
    }

    public long elapsedMillis() {
        if (!running) {
            return accumulated;
        }
        return accumulated + Math.max(0, millis.getAsLong() - segmentStart);
    }
}
