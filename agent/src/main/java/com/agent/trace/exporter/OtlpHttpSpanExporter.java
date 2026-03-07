package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.span.AttributeKey;
import com.agent.span.ReadableSpan;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanEvent;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * OTLP/HTTP JSON 방식으로 Span을 OTel Collector로 전송한다.
 * 외부 의존성 없이 Java 11 HttpClient + 수동 JSON 직렬화 사용.
 */
public final class OtlpHttpSpanExporter implements SpanExporter {
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAY_MS = {0L, 1000L, 5000L};

    private final URI endpoint;
    private final String serviceName;
    private final HttpClient httpClient;

    public OtlpHttpSpanExporter(String endpoint, String serviceName) {
        this.endpoint = URI.create(endpoint);
        this.serviceName = serviceName;
        ThreadFactory daemon = r -> {
            Thread t = new Thread(r, "javi-otlp-exporter");
            t.setDaemon(true);
            return t;
        };
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(2, daemon))
                .build();
    }

    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        String json = toJson(spans, serviceName);
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAY_MS[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableResultCode.ofFailure();
                }
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    AgentLogger.debug("OTLP export success spans=" + spans.size() + " status=" + status);
                    return CompletableResultCode.ofSuccess();
                }
                // 4xx는 재시도 없음
                if (status >= 400 && status < 500) {
                    AgentLogger.warn("OTLP export rejected status=" + status + " endpoint=" + endpoint);
                    return CompletableResultCode.ofFailure();
                }
                // 5xx는 재시도
                AgentLogger.warn("OTLP export failed status=" + status + " attempt=" + (attempt + 1) + "/" + MAX_RETRIES);
            } catch (Exception e) {
                AgentLogger.warn("OTLP export error attempt=" + (attempt + 1) + "/" + MAX_RETRIES + ": " + e.getMessage());
            }
        }
        return CompletableResultCode.ofFailure();
    }

    // ---- JSON 직렬화 ----

    static String toJson(Collection<Span> spans, String serviceName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");
        appendStringAttr(sb, "service.name", serviceName);
        sb.append(",");
        appendStringAttr(sb, "telemetry.sdk.name", "javi-agent");
        sb.append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"agent-auto\",\"version\":\"1.0.0\"},\"spans\":[");

        boolean firstSpan = true;
        for (Span span : spans) {
            if (!(span instanceof ReadableSpan)) continue;
            ReadableSpan rs = (ReadableSpan) span;
            if (!firstSpan) sb.append(",");
            firstSpan = false;
            appendSpan(sb, rs);
        }

        sb.append("]}]}]}");
        return sb.toString();
    }

    private static void appendSpan(StringBuilder sb, ReadableSpan rs) {
        SpanContext ctx = rs.getContext();
        sb.append("{");
        sb.append("\"traceId\":\"").append(ctx.getTraceId()).append("\",");
        sb.append("\"spanId\":\"").append(ctx.getSpanId()).append("\",");
        String parentId = ctx.getParentSpanId();
        if (parentId != null && !parentId.equals("0000000000000000")) {
            sb.append("\"parentSpanId\":\"").append(parentId).append("\",");
        }
        sb.append("\"name\":\"").append(escapeJson(rs.getName())).append("\",");
        sb.append("\"kind\":").append(kindToOtlp(rs.getKind())).append(",");
        sb.append("\"startTimeUnixNano\":\"").append(rs.getStartTimeNanos()).append("\",");
        sb.append("\"endTimeUnixNano\":\"").append(rs.getEndTimeNanos()).append("\",");

        // attributes
        sb.append("\"attributes\":[");
        appendAttributes(sb, rs.getAttributes());
        sb.append("],");

        // events
        sb.append("\"events\":[");
        boolean firstEvent = true;
        for (SpanEvent event : rs.getEvents()) {
            if (!firstEvent) sb.append(",");
            firstEvent = false;
            sb.append("{\"timeUnixNano\":\"").append(event.getTimestampNanos()).append("\",");
            sb.append("\"name\":\"").append(escapeJson(event.getName())).append("\",");
            sb.append("\"attributes\":[");
            appendAttributes(sb, event.getAttributes());
            sb.append("]}");
        }
        sb.append("],");

        // status
        sb.append("\"status\":{\"code\":").append(statusToOtlp(rs.getStatus()));
        if (rs.getStatusDescription() != null && !rs.getStatusDescription().isEmpty()) {
            sb.append(",\"message\":\"").append(escapeJson(rs.getStatusDescription())).append("\"");
        }
        sb.append("}");

        sb.append("}");
    }

    private static void appendAttributes(StringBuilder sb, Map<AttributeKey<?>, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return;
        boolean first = true;
        for (Map.Entry<AttributeKey<?>, Object> entry : attrs.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"key\":\"").append(escapeJson(entry.getKey().getKey())).append("\",\"value\":");
            appendAttrValue(sb, entry.getValue());
            sb.append("}");
        }
    }

    private static void appendAttrValue(StringBuilder sb, Object value) {
        if (value instanceof String) {
            sb.append("{\"stringValue\":\"").append(escapeJson((String) value)).append("\"}");
        } else if (value instanceof Long) {
            // OTLP uses string for int64
            sb.append("{\"intValue\":\"").append(value).append("\"}");
        } else if (value instanceof Double) {
            sb.append("{\"doubleValue\":").append(value).append("}");
        } else if (value instanceof Boolean) {
            sb.append("{\"boolValue\":").append(value).append("}");
        } else if (value != null) {
            sb.append("{\"stringValue\":\"").append(escapeJson(value.toString())).append("\"}");
        } else {
            sb.append("{\"stringValue\":\"\"}");
        }
    }

    private static void appendStringAttr(StringBuilder sb, String key, String value) {
        sb.append("{\"key\":\"").append(key).append("\",\"value\":{\"stringValue\":\"")
                .append(escapeJson(value)).append("\"}}");
    }

    private static int kindToOtlp(SpanKind kind) {
        if (kind == null) return 1;
        switch (kind) {
            case SERVER: return 2;
            case CLIENT: return 3;
            case PRODUCER: return 4;
            case CONSUMER: return 5;
            default: return 1; // INTERNAL
        }
    }

    private static int statusToOtlp(SpanStatus status) {
        if (status == null) return 0;
        switch (status) {
            case OK: return 1;
            case ERROR: return 2;
            default: return 0; // UNSET
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
