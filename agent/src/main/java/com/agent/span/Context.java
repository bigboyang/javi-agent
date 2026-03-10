package com.agent.span;

import com.agent.propagation.Baggage;

/**
 * 현재 스팬 및 비즈니스 맥락(Baggage)을 관리하는 ThreadLocal 컨텍스트.
 */
public final class Context {
    private static final ThreadLocal<Span> CURRENT_SPAN = new ThreadLocal<>();
    private static final ThreadLocal<Baggage> CURRENT_BAGGAGE = new ThreadLocal<>();

    private Context() {}

    /** 현재 활성화된 Span을 반환한다. */
    public static Span currentSpan() {
        Span span = CURRENT_SPAN.get();
        return span == null ? Span.invalid() : span;
    }

    /** 현재 활성화된 Baggage를 반환한다. (없으면 empty) */
    public static Baggage currentBaggage() {
        Baggage baggage = CURRENT_BAGGAGE.get();
        return baggage == null ? Baggage.empty() : baggage;
    }

    /** Span을 현재 컨텍스트로 설정한다. */
    public static Scope makeCurrent(Span span) {
        Span previous = CURRENT_SPAN.get();
        CURRENT_SPAN.set(span);
        return new SpanScope(previous);
    }

    /** Baggage를 현재 컨텍스트로 설정한다. (전파 시 사용) */
    public static Scope makeCurrent(Baggage baggage) {
        Baggage previous = CURRENT_BAGGAGE.get();
        CURRENT_BAGGAGE.set(baggage);
        return new BaggageScope(previous);
    }

    static void restore(Span previous) {
        if (previous == null) {
            CURRENT_SPAN.remove();
        } else {
            CURRENT_SPAN.set(previous);
        }
    }

    private static void restoreBaggage(Baggage previous) {
        if (previous == null) {
            CURRENT_BAGGAGE.remove();
        } else {
            CURRENT_BAGGAGE.set(previous);
        }
    }

    private static final class SpanScope implements Scope {
        private final Span previous;

        private SpanScope(Span previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            restore(previous);
        }
    }

    private static final class BaggageScope implements Scope {
        private final Baggage previous;

        private BaggageScope(Baggage previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            restoreBaggage(previous);
        }
    }
}
