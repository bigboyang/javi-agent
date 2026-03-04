package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;

/** Processor that ignores all spans. */
public final class NoopSpanProcessor implements SpanProcessor {
    private static final NoopSpanProcessor INSTANCE = new NoopSpanProcessor();

    private NoopSpanProcessor() {}

    public static NoopSpanProcessor getInstance() {
        return INSTANCE;
    }

    @Override
    public void onStart(Span span) {
        // no-op
    }

    @Override
    public void onEnd(Span span) {
        // no-op
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
