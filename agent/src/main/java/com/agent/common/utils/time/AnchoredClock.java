package com.agent.common.utils.time;

/**
 * Computes epoch timestamps based on a single anchor point and monotonic time.
 */
public final class AnchoredClock {
    private final Clock clock;
    private final long epochNanos;
    private final long nanoTime;

    private AnchoredClock(Clock clock, long epochNanos, long nanoTime) {
        this.clock = clock;
        this.epochNanos = epochNanos;
        this.nanoTime = nanoTime;
    }

    public static AnchoredClock create(Clock clock) {
        Clock resolved = clock == null ? Clock.system() : clock;
        return new AnchoredClock(resolved, resolved.now(), resolved.nanoTime());
    }

    public long now() {
        long deltaNanos = clock.nanoTime() - nanoTime;
        return epochNanos + deltaNanos;
    }

    public long startTime() {
        return epochNanos;
    }
}
