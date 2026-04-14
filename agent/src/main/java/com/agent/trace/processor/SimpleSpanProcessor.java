package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import com.agent.trace.exporter.SpanExporter;
import java.util.Collections;

/** Exports spans synchronously on end. */
public final class SimpleSpanProcessor implements SpanProcessor {
    private final SpanExporter exporter;

    public SimpleSpanProcessor(SpanExporter exporter) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter");
        }
        this.exporter = exporter;
    }

    @Override
    public void onStart(Span span) {
        // no-op
    }

    @Override
    public void onEnd(Span span) {
        if (span == null) {
            return;
        }
        if (com.agent.config.RemoteConfigHolder.get().isEmergencyOff()) {
            return;
        }
        if (!span.getContext().getTraceFlags().isSampled()) {
            return;
        }
        exporter.export(Collections.singletonList(span));
    }

    @Override
    public CompletableResultCode forceFlush() {
        return exporter.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return exporter.shutdown();
    }
}
