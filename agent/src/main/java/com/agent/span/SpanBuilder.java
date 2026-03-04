package com.agent.span;

public interface SpanBuilder {

    SpanBuilder setParent(SpanContext parent);
    SpanBuilder setNoParent();
    SpanBuilder addLink(SpanContext spanContext);
    SpanBuilder addLink(SpanContext spanContext, java.util.Map<AttributeKey<?>, Object> attributes);
    SpanBuilder setSpanKind(SpanKind spanKind);
    SpanBuilder setStartTimestamp(long startTimestampNanos);
    Span startSpan();
}
