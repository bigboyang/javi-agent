package com.agent.span;

/**
 * 스팬 인터페이스.
 */
public interface Span {
    static Span invalid() {
        return NoopSpan.INSTANCE;
    }

    static Span wrap(SpanContext context) {
        if (context == null) {
            return invalid();
        }
        return new PropagatedSpan(context, com.agent.common.utils.time.AnchoredClock.create(
                com.agent.common.utils.time.Clock.system()));
    }

    static Span wrap(SpanContext context, com.agent.common.utils.time.AnchoredClock anchoredClock) {
        if (context == null) {
            return invalid();
        }
        return new PropagatedSpan(context, anchoredClock);
    }

    Scope makeCurrent();

    void end();

    String getName();

    Span updateName(String name);

    default boolean isRecording() { return false; }

    SpanContext getContext();

    long getStartTimeNanos();

    long getEndTimeNanos();

    SpanKind getKind();

    Span setAttribute(String key, String value);

    Span setAttribute(String key, long value);

    Span setAttribute(String key, double value);

    Span setAttribute(String key, boolean value);

    <T> Span setAttribute(AttributeKey<T> key, T value);

    Span addEvent(String name);

    Span addEvent(String name, java.util.Map<AttributeKey<?>, Object> attributes);

    Span recordException(Throwable exception);

    Span setStatus(SpanStatus status, String description);
}
