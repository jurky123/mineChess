package com.minechess.match;

import java.util.function.LongSupplier;

/**
 * 棋钟：用真实时间（nanoTime）而不是服务器 tick 计算，TPS 波动不会影响计时。
 * casual（不限时）时永远返回 Long.MAX_VALUE 且不会超时。
 */
public final class ChessClock {

    private final boolean casual;
    private final long incrementMillis;
    private final LongSupplier nanoTime;
    private long remainingMillis;
    private long startedAtNanos = -1;

    public ChessClock(long initialMillis, long incrementMillis, LongSupplier nanoTime) {
        this.casual = initialMillis <= 0;
        this.incrementMillis = incrementMillis;
        this.nanoTime = nanoTime;
        this.remainingMillis = Math.max(0, initialMillis);
    }

    public static ChessClock system(long initialMillis, long incrementMillis) {
        return new ChessClock(initialMillis, incrementMillis, System::nanoTime);
    }

    public boolean casual() {
        return casual;
    }

    public boolean running() {
        return startedAtNanos >= 0;
    }

    public void start() {
        if (!casual) startedAtNanos = nanoTime.getAsLong();
    }

    /** 停止计时并返回本步耗时（毫秒）。 */
    public long stopAndElapsed() {
        if (startedAtNanos < 0 || casual) {
            startedAtNanos = -1;
            return 0;
        }
        long elapsed = Math.max(0, (nanoTime.getAsLong() - startedAtNanos) / 1_000_000L);
        remainingMillis = Math.max(0, remainingMillis - elapsed);
        startedAtNanos = -1;
        return elapsed;
    }

    /** 走子后加秒（Fischer increment）。 */
    public void addIncrement() {
        if (!casual) remainingMillis += incrementMillis;
    }

    public long remainingMillis() {
        if (casual) return Long.MAX_VALUE;
        if (startedAtNanos < 0) return remainingMillis;
        long elapsed = Math.max(0, (nanoTime.getAsLong() - startedAtNanos) / 1_000_000L);
        return Math.max(0, remainingMillis - elapsed);
    }

    public boolean flagged() {
        return !casual && remainingMillis() <= 0;
    }

    /** mm:ss 或 h:mm:ss。 */
    public String format() {
        if (casual) return "∞";
        long totalSeconds = remainingMillis() / 1000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }
}
