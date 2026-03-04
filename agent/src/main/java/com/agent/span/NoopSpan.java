package com.agent.span;

/**
 * 비활성 상태에서 사용하는 no-op 스팬.
 */
final class NoopSpan implements Span {
    static final NoopSpan INSTANCE = new NoopSpan();

    private NoopSpan() {}

    @Override
    public Scope makeCurrent() {
        return Context.makeCurrent(this);
    }

    @Override
    public void end() {
    }

    @Override
    public String getName() {
        return "invalid";
    }

    @Override
    public SpanContext getContext() {
        return SpanContext.invalid();
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
}
