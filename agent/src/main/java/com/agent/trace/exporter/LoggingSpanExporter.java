package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.util.Collection;

/** Logs span data to JUL for debugging. */
public final class LoggingSpanExporter implements SpanExporter {
    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        for (Span span : spans) {
            if (span == null) {
                continue;
            }
            SpanContext context = span.getContext();
            System.out.println(
                    "[agent] export span name=" + span.getName()
                            + " traceId=" + (context == null ? "unknown" : context.getTraceId())
                            + " spanId=" + (context == null ? "unknown" : context.getSpanId())
                            + " kind=" + span.getKind()
                            + " startNanos=" + span.getStartTimeNanos()
                            + " endNanos=" + span.getEndTimeNanos());
        }
        return CompletableResultCode.ofSuccess();
    }
}
