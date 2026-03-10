package com.agent.trace.exporter;

import com.agent.common.OtlpHttpSender;
import com.agent.common.OtlpHttpSender.SendResult;
import com.agent.common.ResourceInfo;
import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.span.AttributeKey;
import com.agent.span.ReadableSpan;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanEvent;
import com.agent.span.SpanKind;
import com.agent.span.SpanLink;
import com.agent.span.SpanStatus;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP/HTTP JSON 방식으로 Span을 OTel Collector로 전송한다.
 *
 * <p>OTLP Traces 스펙 (opentelemetry-proto/trace/v1/trace.proto):
 * <pre>
 * ExportTraceServiceRequest
 *   └─ resourceSpans[]
 *        ├─ resource { attributes[] }
 *        └─ scopeSpans[]
 *             ├─ scope { name, version }
 *             └─ spans[]
 *                  ├─ traceId, spanId, parentSpanId
 *                  ├─ name, kind
 *                  ├─ startTimeUnixNano, endTimeUnixNano
 *                  ├─ attributes[], droppedAttributesCount
 *                  ├─ events[], droppedEventsCount
 *                  ├─ links[]
 *                  └─ status { code, message }
 * </pre>
 *
 * <p>설계 변경 (OtlpHttpSender 위임):
 * <ul>
 *   <li>HTTP 재시도 / 타임아웃 / 헤더 관리를 OtlpHttpSender에 위임</li>
 *   <li>이 클래스는 Span → JSON 직렬화와 엔드포인트 라우팅만 담당</li>
 *   <li>자체 HttpClient 미보유 → 연결 자원 중복 방지</li>
 * </ul>
 *
 * <p>SpanDrop 필터: RemoteConfigHolder.get().getSpanDrop()의 키에 해당하는 속성은
 * 직렬화에서 제외되어 네트워크와 백엔드 저장소 부하를 줄인다.
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_COLLECTOR_ENDPOINT / javi.collector.endpoint (기본: http://localhost:4318)</li>
 *   <li>JAVI_COLLECTOR_TIMEOUT_MS / javi.collector.timeout.ms (기본: 10000)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name (기본: javi-service)</li>
 * </ul>
 */
public final class OtlpHttpSpanExporter implements SpanExporter {

    private static final String PATH = "/v1/traces";

    // ---- 내부 상태 ----
    private final URI endpoint;
    private final String serviceName;
    private final OtlpHttpSender sender;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // 파이프라인 헬스 카운터
    private final AtomicLong exportedSpans  = new AtomicLong(0);
    private final AtomicLong droppedSpans   = new AtomicLong(0);
    private final AtomicLong failedBatches  = new AtomicLong(0);

    // ---- 생성자 ----

    /**
     * 환경변수/시스템 프로퍼티에서 설정을 읽어 기본 인스턴스를 생성한다.
     * AgentRuntime이 endpoint / serviceName을 직접 지정할 때는 아래 생성자를 사용한다.
     */
    public OtlpHttpSpanExporter() {
        this(
            resolveEndpoint(),
            resolveServiceName(),
            OtlpHttpSender.create()
        );
    }

    /**
     * AgentRuntime / 테스트에서 명시적으로 endpoint와 serviceName을 지정할 때 사용한다.
     *
     * @param endpointBase 기본 URL (예: http://localhost:4318 또는 http://localhost:4318/v1/traces)
     * @param serviceName  service.name 리소스 속성 값
     */
    public OtlpHttpSpanExporter(String endpointBase, String serviceName) {
        this(endpointBase, serviceName, OtlpHttpSender.create());
    }

    /**
     * 테스트 또는 공유 sender 주입용 생성자.
     *
     * @param endpointBase 기본 URL
     * @param serviceName  service.name 리소스 속성 값
     * @param sender       공유 또는 전용 OtlpHttpSender 인스턴스
     */
    public OtlpHttpSpanExporter(String endpointBase, String serviceName, OtlpHttpSender sender) {
        String base = endpointBase.endsWith(PATH)
                ? endpointBase
                : endpointBase.replaceAll("/+$", "") + PATH;
        this.endpoint    = URI.create(base);
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    // ---- SpanExporter 구현 ----

    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        if (isShutdown.get() || spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }

        String json = toJson(spans, serviceName);
        SendResult result = sender.send(endpoint, json);

        if (result == SendResult.SUCCESS) {
            exportedSpans.addAndGet(spans.size());
            AgentLogger.debug("OtlpHttpSpanExporter: export 성공 spans=" + spans.size());
            return CompletableResultCode.ofSuccess();
        }

        droppedSpans.addAndGet(spans.size());
        failedBatches.incrementAndGet();
        AgentLogger.warn("OtlpHttpSpanExporter: 배치 전송 실패 spans=" + spans.size()
                + " dropped_total=" + droppedSpans.get());
        return CompletableResultCode.ofFailure();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            AgentLogger.info("OtlpHttpSpanExporter shutdown: exported=" + exportedSpans.get()
                    + " dropped=" + droppedSpans.get()
                    + " failed_batches=" + failedBatches.get());
            // sender의 shutdown은 소유권에 따라 호출자가 결정.
            // 단독 소유(AgentRuntime이 이 exporter 전용으로 생성한 경우)면 아래 주석 해제.
            // sender.shutdown();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() {
        shutdown().join(10, TimeUnit.SECONDS);
    }

    // ---- JSON 직렬화 ----

    /**
     * Span 컬렉션을 OTLP ExportTraceServiceRequest JSON으로 직렬화한다.
     *
     * <p>InstrumentationScope 단위 그룹핑은 생략하고 단일 scopeSpan에 모든 span을 넣는다.
     * (그룹핑 비용 대비 Collector 처리에 영향 없음)
     *
     * <p>패키지-프라이빗: 단위 테스트에서 직접 검증 가능.
     */
    static String toJson(Collection<Span> spans, String serviceName) {
        // 예상 용량: 스팬당 약 600B
        StringBuilder sb = new StringBuilder(spans.size() * 600 + 512);

        sb.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");

        // ResourceInfo에서 동적으로 모든 속성 가져오기
        boolean firstResAttr = true;
        for (Map.Entry<String, String> entry : ResourceInfo.getAttributes().entrySet()) {
            if (!firstResAttr) sb.append(",");
            firstResAttr = false;
            appendStringAttr(sb, entry.getKey(), entry.getValue());
        }

        // service.name이 ResourceInfo에 없을 경우를 대비해 (이미 ResourceInfo에 포함되어 있지만 보장)
        if (!ResourceInfo.getAttributes().containsKey("service.name")) {
            sb.append(",");
            appendStringAttr(sb, "service.name", serviceName);
        }

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

        // 식별자
        sb.append("\"traceId\":\"").append(ctx.getTraceId()).append("\",");
        sb.append("\"spanId\":\"").append(ctx.getSpanId()).append("\",");

        String parentId = ctx.getParentSpanId();
        if (parentId != null && !parentId.equals("0000000000000000")) {
            sb.append("\"parentSpanId\":\"").append(parentId).append("\",");
        }

        // 이름 / 종류
        sb.append("\"name\":\"").append(escapeJson(rs.getName())).append("\",");
        sb.append("\"kind\":").append(kindToOtlp(rs.getKind())).append(",");

        // 타임스탬프 (string: int64)
        sb.append("\"startTimeUnixNano\":\"").append(rs.getStartTimeNanos()).append("\",");
        sb.append("\"endTimeUnixNano\":\"").append(rs.getEndTimeNanos()).append("\",");

        // 속성
        sb.append("\"attributes\":[");
        Set<String> dropKeys = com.agent.config.RemoteConfigHolder.get().getSpanDrop();
        appendSpanAttributes(sb, rs.getAttributes(), dropKeys);
        sb.append("],");

        // droppedAttributesCount
        int droppedAttrs = rs.getDroppedAttributeCount();
        if (droppedAttrs > 0) {
            sb.append("\"droppedAttributesCount\":").append(droppedAttrs).append(",");
        }

        // events
        sb.append("\"events\":[");
        appendEvents(sb, rs.getEvents());
        sb.append("],");

        // droppedEventsCount
        int droppedEvents = rs.getDroppedEventCount();
        if (droppedEvents > 0) {
            sb.append("\"droppedEventsCount\":").append(droppedEvents).append(",");
        }

        // links
        sb.append("\"links\":[");
        appendLinks(sb, rs.getLinks());
        sb.append("],");

        // status
        sb.append("\"status\":{\"code\":").append(statusToOtlp(rs.getStatus()));
        String desc = rs.getStatusDescription();
        if (desc != null && !desc.isEmpty()) {
            sb.append(",\"message\":\"").append(escapeJson(desc)).append("\"");
        }
        sb.append("}");

        sb.append("}");
    }

    private static void appendSpanAttributes(
            StringBuilder sb,
            Map<AttributeKey<?>, Object> attrs,
            Set<String> dropKeys) {

        if (attrs == null || attrs.isEmpty()) return;

        boolean first = true;
        for (Map.Entry<AttributeKey<?>, Object> entry : attrs.entrySet()) {
            String key = entry.getKey().getKey();
            // spanDrop 필터: 원격 설정에서 지정된 키는 내보내지 않음
            if (!dropKeys.isEmpty() && dropKeys.contains(key)) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"key\":\"").append(escapeJson(key)).append("\",\"value\":");
            appendAttrValue(sb, entry.getValue());
            sb.append("}");
        }
    }

    private static void appendEvents(StringBuilder sb, java.util.List<SpanEvent> events) {
        if (events == null || events.isEmpty()) return;

        boolean first = true;
        for (SpanEvent event : events) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"timeUnixNano\":\"").append(event.getTimestampNanos()).append("\",");
            sb.append("\"name\":\"").append(escapeJson(event.getName())).append("\",");
            sb.append("\"attributes\":[");
            appendEventAttributes(sb, event.getAttributes());
            sb.append("]}");
        }
    }

    private static void appendEventAttributes(StringBuilder sb, Map<AttributeKey<?>, Object> attrs) {
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

    private static void appendLinks(StringBuilder sb, java.util.List<SpanLink> links) {
        if (links == null || links.isEmpty()) return;
        boolean first = true;
        for (SpanLink link : links) {
            if (!first) sb.append(",");
            first = false;
            SpanContext ctx = link.getSpanContext();
            sb.append("{");
            sb.append("\"traceId\":\"").append(ctx.getTraceId()).append("\",");
            sb.append("\"spanId\":\"").append(ctx.getSpanId()).append("\",");
            sb.append("\"attributes\":[]");
            sb.append("}");
        }
    }

    // ---- 타입 변환 ----

    /**
     * OTLP SpanKind 매핑.
     * INTERNAL=1, SERVER=2, CLIENT=3, PRODUCER=4, CONSUMER=5
     */
    private static int kindToOtlp(SpanKind kind) {
        if (kind == null) return 1;
        switch (kind) {
            case SERVER:   return 2;
            case CLIENT:   return 3;
            case PRODUCER: return 4;
            case CONSUMER: return 5;
            default:       return 1; // INTERNAL
        }
    }

    /**
     * OTLP Status.StatusCode 매핑.
     * STATUS_CODE_UNSET=0, STATUS_CODE_OK=1, STATUS_CODE_ERROR=2
     */
    private static int statusToOtlp(SpanStatus status) {
        if (status == null) return 0;
        switch (status) {
            case OK:    return 1;
            case ERROR: return 2;
            default:    return 0; // UNSET
        }
    }

    /**
     * AttributeValue → OTLP AnyValue JSON.
     * OTLP int64는 string으로 직렬화 (JSON number 정밀도 손실 방지).
     */
    private static void appendAttrValue(StringBuilder sb, Object value) {
        if (value instanceof String) {
            sb.append("{\"stringValue\":\"").append(escapeJson((String) value)).append("\"}");
        } else if (value instanceof Long) {
            sb.append("{\"intValue\":\"").append(value).append("\"}");
        } else if (value instanceof Integer) {
            sb.append("{\"intValue\":\"").append(value).append("\"}");
        } else if (value instanceof Double || value instanceof Float) {
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
        sb.append("{\"key\":\"").append(escapeJson(key))
          .append("\",\"value\":{\"stringValue\":\"").append(escapeJson(value)).append("\"}}");
    }

    /**
     * JSON 특수문자 이스케이프.
     * null 입력은 빈 문자열로 처리한다.
     */
    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ---- 설정 읽기 ----

    private static String resolveEndpoint() {
        return OtlpHttpSender.get(
                "JAVI_COLLECTOR_ENDPOINT", "javi.collector.endpoint",
                "http://localhost:4318");
    }

    private static String resolveServiceName() {
        return OtlpHttpSender.get(
                "JAVI_SERVICE_NAME", "javi.service.name",
                "javi-service");
    }
}
