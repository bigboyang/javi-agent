package com.agent.span;

import com.agent.propagation.Baggage;

/**
 * 현재 스팬 및 비즈니스 맥락(Baggage)을 관리하는 ThreadLocal 컨텍스트.
 */
public final class Context {
    // InheritableThreadLocal: 자식 스레드(Virtual Thread 포함)가 부모의 span context를 자동 상속.
    // Virtual Thread는 생성 시점(= 태스크 제출 시점)에 값이 복사되어 올바른 propagation을 제공한다.
    // 플랫폼 스레드 풀에서는 ContextPropagatingRunnable의 명시적 restore()가 이 값을 덮어쓰므로 안전하다.
    private static final InheritableThreadLocal<Span> CURRENT_SPAN = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<Baggage> CURRENT_BAGGAGE = new InheritableThreadLocal<>();

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
