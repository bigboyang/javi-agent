package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.logs.TraceLogger;
import com.agent.span.ReadableSpan;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.util.Collection;

/**
 * span 데이터를 TraceLogger를 통해 별도 trace 파일에 MDC 형태로 기록한다.
 *
 * <p>AgentLogger(내부 운영 로그)와 분리하여 span 수집 결과만 javi-traces.log에 저장한다.
 */
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
            String traceId = context == null ? "unknown" : context.getTraceId();
            String spanId = context == null ? "unknown" : context.getSpanId();
            String parentSpanId = context == null ? "unknown" : context.getParentSpanId();
            String kind = span.getKind() == null ? "INTERNAL" : span.getKind().name();

            long durationMs = 0;
            String status = "UNSET";
            String attrs = null;

            if (span instanceof ReadableSpan) {
                ReadableSpan rs = (ReadableSpan) span;
                durationMs = (rs.getEndTimeNanos() - rs.getStartTimeNanos()) / 1_000_000L;
                status = rs.getStatus() == null ? "UNSET" : rs.getStatus().name();
                if (!rs.getAttributes().isEmpty()) {
                    attrs = rs.getAttributes().toString();
                }
            }

            // MDC 구조화 로그: TraceLogger → javi-traces.log
            TraceLogger.trace(traceId, spanId, parentSpanId,
                    span.getName(), kind, durationMs, status, attrs);

            // 운영 디버그용 요약 로그: AgentLogger → javi-agent.log
            AgentLogger.debug("[span-export] traceId=" + traceId
                    + " operation=" + span.getName()
                    + " durationMs=" + durationMs
                    + " status=" + status);
        }
        return CompletableResultCode.ofSuccess();
    }
}
