package com.agent.span;

import java.util.Map;

/**
 * 비활성 상태에서 사용하는 no-op SpanBuilder.
 */
public final class NoopSpanBuilder implements SpanBuilder {
    public static final NoopSpanBuilder INSTANCE = new NoopSpanBuilder();

    private NoopSpanBuilder() {}

    @Override
    public SpanBuilder setParent(SpanContext parent) {
        return this;
    }

    @Override
    public SpanBuilder setNoParent() {
        return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext) {
        return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext, Map<AttributeKey<?>, Object> attributes) {
        return this;
    }

    @Override
    public SpanBuilder setSpanKind(SpanKind spanKind) {
        return this;
    }

    @Override
    public SpanBuilder setStartTimestamp(long startTimestampNanos) {
        return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, String value) { return this; }

    @Override
    public SpanBuilder setAttribute(String key, long value) { return this; }

    @Override
    public SpanBuilder setAttribute(String key, double value) { return this; }

    @Override
    public SpanBuilder setAttribute(String key, boolean value) { return this; }

    @Override
    public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) { return this; }

    @Override
    public Span startSpan() {
        return Span.invalid();
    }
}
