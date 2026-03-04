package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import java.util.Collection;

/** Exporter that drops all spans. */
public final class NoopSpanExporter implements SpanExporter {
    private static final NoopSpanExporter INSTANCE = new NoopSpanExporter();

    private NoopSpanExporter() {}

    public static NoopSpanExporter getInstance() {
        return INSTANCE;
    }

    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        return CompletableResultCode.ofSuccess();
    }
}
