package com.agent.common.utils.time;

/**
 * Time source for absolute (epoch) and monotonic timestamps.
 */
public interface Clock {
    long now();

    long nanoTime();

    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
