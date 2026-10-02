package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RunClockTest {
    @Test
    void onlyCountsTimeWhileRunning() {
        AtomicLong now = new AtomicLong(1_000);
        RunClock clock = new RunClock(now::get);
        clock.start();
        now.addAndGet(500);
        clock.stop();
        now.addAndGet(10_000);
        assertEquals(500, clock.elapsedMillis());
        clock.start();
        now.addAndGet(250);
        assertEquals(750, clock.elapsedMillis());
    }

    @Test
    void formatsAsHoursMinutesSeconds() {
        assertEquals("0:00:00", TimeFormat.clock(0));
        assertEquals("1:02:03", TimeFormat.clock(3_723_000));
        assertEquals("0:00:00", TimeFormat.clock(-5));
    }
}
