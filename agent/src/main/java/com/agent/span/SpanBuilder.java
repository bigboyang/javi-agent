package com.agent.span;

public interface SpanBuilder {

    SpanBuilder setParent(SpanContext parent);
    SpanBuilder setNoParent();
    SpanBuilder addLink(SpanContext spanContext);
    SpanBuilder addLink(SpanContext spanContext, java.util.Map<AttributeKey<?>, Object> attributes);
    SpanBuilder setSpanKind(SpanKind spanKind);
    SpanBuilder setStartTimestamp(long startTimestampNanos);
    SpanBuilder setAttribute(String key, String value);
    SpanBuilder setAttribute(String key, long value);
    SpanBuilder setAttribute(String key, double value);
    SpanBuilder setAttribute(String key, boolean value);
    <T> SpanBuilder setAttribute(AttributeKey<T> key, T value);
    Span startSpan();
}
