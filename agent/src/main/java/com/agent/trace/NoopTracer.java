package com.agent.trace;

import com.agent.span.NoopSpanBuilder;
import com.agent.span.SpanBuilder;

/**
 * 비활성 상태에서 사용하는 no-op Tracer.
 */
final class NoopTracer implements Tracer {
    static final NoopTracer INSTANCE = new NoopTracer();

    private NoopTracer() {}

    @Override
    public SpanBuilder spanBuilder(String name) {
        return NoopSpanBuilder.INSTANCE;
    }
}
