package com.agent.common.utils.time;

import java.time.Instant;

/**
 * Default clock implementation based on system time.
 */
final class SystemClock implements Clock {
    static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {}

    @Override
    public long now() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
