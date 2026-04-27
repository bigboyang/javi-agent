package com.agent.metric;

import java.util.Collections;
import java.util.Map;

/**
 * Metric-Trace 연결을 위한 Exemplar (OTel 표준).
 *
 * <p>상용 APM(Datadog, Grafana Mimir, New Relic)과 동일한 구조:
 * <ul>
 *   <li>traceId / spanId — 메트릭 이상치에서 원인 트레이스로 직접 드릴다운</li>
 *   <li>traceFlags — OTel Collector/Grafana Mimir가 span sampled 여부 확인에 사용</li>
 *   <li>filteredAttributes — HTTP method, status code 등 필터링된 속성</li>
 *   <li>value — 실제 측정값 (레이턴시 ms)</li>
 * </ul>
 *
 * <p>OTLP proto field numbers (opentelemetry-proto/metrics/v1/metrics.proto):
 * <pre>
 *   Exemplar.filtered_attributes = 7 (repeated KeyValue)
 *   Exemplar.time_unix_nano       = 2 (fixed64)
 *   Exemplar.as_double            = 3 (double, oneof value)
 *   Exemplar.span_id              = 4 (bytes)
 *   Exemplar.trace_id             = 5 (bytes)
 *   Exemplar.flags                = 8 (uint32 — SpanFlags, SAMPLED=0x01)
 * </pre>
 */
public final class Exemplar {

    private final double value;
    private final long timeUnixNano;
    private final String traceId;   // 32-char hex
    private final String spanId;    // 16-char hex
    private final byte traceFlags;  // OTel SpanFlags (SAMPLED=0x01)
    private final Map<String, String> filteredAttributes;

    Exemplar(double value, long timeUnixNano, String traceId, String spanId,
             byte traceFlags, Map<String, String> filteredAttributes) {
        this.value               = value;
        this.timeUnixNano        = timeUnixNano;
        this.traceId             = traceId;
        this.spanId              = spanId;
        this.traceFlags          = traceFlags;
        this.filteredAttributes  = filteredAttributes != null
                ? filteredAttributes : Collections.emptyMap();
    }

    public double              getValue()              { return value; }
    public long                getTimeUnixNano()       { return timeUnixNano; }
    public String              getTraceId()            { return traceId; }
    public String              getSpanId()             { return spanId; }
    public byte                getTraceFlags()         { return traceFlags; }
    public Map<String, String> getFilteredAttributes() { return filteredAttributes; }
}
