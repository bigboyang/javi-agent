package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;

/**
 * Receives span lifecycle callbacks and forwards spans to exporters or other processors.
 */
public interface SpanProcessor {
    /** Called when a span is started. */
    void onStart(Span span);

    /** Called when a span is ended. */
    void onEnd(Span span);

    /** Flush buffered spans if any. */
    default CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    /** Shutdown and release resources. */
    default CompletableResultCode shutdown() {
        return forceFlush();
    }
}
