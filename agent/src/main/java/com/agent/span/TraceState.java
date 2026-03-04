package com.agent.span;

/**
 * TraceState를 단순화한 표현.
 */
public final class TraceState {
    private static final TraceState DEFAULT = new TraceState("");

    private final String value;

    private TraceState(String value) {
        this.value = value;
    }

    public static TraceState getDefault() {
        return DEFAULT;
    }

    public static TraceState fromHeader(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return DEFAULT;
        }
        return new TraceState(headerValue);
    }

    public String getValue() {
        return value;
    }
}
