package com.agent.span;

import com.agent.common.utils.time.AnchoredClock;

/**
 * Span wrapper for propagating an extracted SpanContext.
 */
final class PropagatedSpan implements Span {
    private final SpanContext context;
    private final AnchoredClock anchoredClock;

    PropagatedSpan(SpanContext context, AnchoredClock anchoredClock) {
        this.context = context;
        this.anchoredClock = anchoredClock;
    }

    @Override
    public Scope makeCurrent() {
        return Context.makeCurrent(this);
    }

    @Override
    public void end() {
    }

    @Override
    public String getName() {
        return "propagated";
    }

    @Override
    public SpanContext getContext() {
        return context;
    }

    AnchoredClock getAnchoredClock() {
        return anchoredClock;
    }

    @Override
    public long getStartTimeNanos() {
        return 0;
    }

    @Override
    public long getEndTimeNanos() {
        return 0;
    }

    @Override
    public SpanKind getKind() {
        return SpanKind.INTERNAL;
    }

    @Override
    public Span setAttribute(String key, String value) {
        return this;
    }

    @Override
    public Span setAttribute(String key, long value) {
        return this;
    }

    @Override
    public Span setAttribute(String key, double value) {
        return this;
    }

    @Override
    public Span setAttribute(String key, boolean value) {
        return this;
    }

    @Override
    public Span addEvent(String name) {
        return this;
    }

    @Override
    public Span addEvent(String name, java.util.Map<AttributeKey<?>, Object> attributes) {
        return this;
    }

    @Override
    public Span recordException(Throwable exception) {
        return this;
    }

    @Override
    public Span setStatus(SpanStatus status, String description) {
        return this;
    }

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
        return this;
    }

    @Override
    public Span updateName(String name) {
        return this;
    }
}
