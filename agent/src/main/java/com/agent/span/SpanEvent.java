package com.agent.span;

import java.util.Collections;
import java.util.Map;

/**
 * Span event data.
 */
public final class SpanEvent {
    private final String name;
    private final long timestampNanos;
    private final Map<AttributeKey<?>, Object> attributes;

    public SpanEvent(String name, long timestampNanos, Map<AttributeKey<?>, Object> attributes) {
        this.name = name;
        this.timestampNanos = timestampNanos;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }

    public String getName() {
        return name;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public Map<AttributeKey<?>, Object> getAttributes() {
        return attributes;
    }
}
