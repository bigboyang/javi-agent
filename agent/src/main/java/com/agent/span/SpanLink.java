package com.agent.span;

import java.util.Collections;
import java.util.Map;

/**
 * Link to another span context.
 */
public final class SpanLink {
    private final SpanContext spanContext;
    private final Map<AttributeKey<?>, Object> attributes;

    public SpanLink(SpanContext spanContext, Map<AttributeKey<?>, Object> attributes) {
        this.spanContext = spanContext;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }

    public SpanContext getSpanContext() {
        return spanContext;
    }

    public Map<AttributeKey<?>, Object> getAttributes() {
        return attributes;
    }
}
