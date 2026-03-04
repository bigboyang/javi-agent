package com.agent.span;

/**
 * 현재 스팬을 관리하는 ThreadLocal 컨텍스트.
 */
public final class Context {
    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();

    private Context() {}

    public static Span currentSpan() {
        Span span = CURRENT.get();
        return span == null ? Span.invalid() : span;
    }

    static Scope makeCurrent(Span span) {
        Span previous = CURRENT.get();
        CURRENT.set(span);
        return new Scope(previous);
    }

    static void restore(Span previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
