package com.agent.span;

/**
 * 현재 스팬을 설정하고 자동으로 복원하기 위한 스코프.
 */
public final class Scope implements AutoCloseable {
    private final Span previous;

    Scope(Span previous) {
        this.previous = previous;
    }

    @Override
    public void close() {
        Context.restore(previous);
    }
}
