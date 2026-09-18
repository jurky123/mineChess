package com.minechess.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChessClockTest {

    @Test
    void countsRealTimeAndIncrement() {
        long[] now = {0};
        ChessClock clock = new ChessClock(60_000, 2_000, () -> now[0]);
        clock.start();
        now[0] += 5_000_000_000L;
        assertEquals(55_000, clock.remainingMillis());
        assertEquals(5_000, clock.stopAndElapsed());
        clock.addIncrement();
        assertEquals(57_000, clock.remainingMillis());
        clock.start();
        now[0] += 60_000_000_000L;
        assertTrue(clock.flagged());
        assertEquals(0, clock.remainingMillis());
        assertEquals("00:00", clock.format());
    }

    @Test
    void tpsIndependent() {
        long[] now = {0};
        ChessClock clock = new ChessClock(10_000, 0, () -> now[0]);
        clock.start();
        now[0] += 1_000_000_000L; // 1 秒
        assertEquals(9_000, clock.remainingMillis());
    }

    @Test
    void casualNeverFlags() {
        ChessClock clock = new ChessClock(0, 0, System::nanoTime);
        clock.start();
        assertFalse(clock.flagged());
        assertTrue(clock.casual());
        assertEquals("∞", clock.format());
        assertEquals(Long.MAX_VALUE, clock.remainingMillis());
    }
}
