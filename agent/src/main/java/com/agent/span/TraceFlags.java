package com.agent.span;

/**
 * TraceFlags를 단순화한 표현.
 */
public final class TraceFlags {
    private static final byte FLAG_SAMPLED = 0x01;
    private static final TraceFlags DEFAULT = new TraceFlags((byte) 0);
    private static final TraceFlags SAMPLED = new TraceFlags(FLAG_SAMPLED);

    private final byte flags;

    private TraceFlags(byte flags) {
        this.flags = flags;
    }

    public static TraceFlags getDefault() {
        return DEFAULT;
    }

    public static TraceFlags sampled() {
        return SAMPLED;
    }

    public boolean isSampled() {
        return (flags & FLAG_SAMPLED) == FLAG_SAMPLED;
    }

    public byte asByte() {
        return flags;
    }
}
