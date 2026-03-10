package com.agent.span;

/**
 * 리소스(Span, Baggage 등)를 현재 컨텍스트로 설정하고 자동으로 복원하기 위한 인터페이스.
 */
public interface Scope extends AutoCloseable {
    @Override
    void close();

    /** 아무 동작도 하지 않는 스코프. */
    static Scope noop() {
        return () -> {};
    }
}
