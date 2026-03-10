package com.agent.logs;

import com.agent.common.DataExporter;
import com.agent.common.OtlpHttpSender;
import com.agent.common.OtlpHttpSender.SendResult;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LogRecord를 OTLP/HTTP JSON 포맷으로 /v1/logs 엔드포인트에 전송하는 Exporter.
 *
 * <p>OTLP Logs 스펙 (opentelemetry-proto/logs/v1/logs.proto):
 * <pre>
 * ExportLogsServiceRequest
 *   └─ resourceLogs[]
 *        ├─ resource { attributes[] }
 *        └─ scopeLogs[]
 *             ├─ scope { name, version }
 *             └─ logRecords[]
 *                  ├─ timeUnixNano (string)
 *                  ├─ severityNumber (int)
 *                  ├─ severityText (string)
 *                  ├─ body { stringValue }
 *                  ├─ traceId (string, hex)
 *                  ├─ spanId (string, hex)
 *                  └─ attributes[]
 * </pre>
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_COLLECTOR_ENDPOINT / javi.collector.endpoint (기본: http://localhost:4318)</li>
 *   <li>JAVI_COLLECTOR_TIMEOUT_MS / javi.collector.timeout.ms (기본: 10000)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name (기본: javi-service)</li>
 * </ul>
 *
 * <p>메모리 안전성:
 * <ul>
 *   <li>StringBuilder 재사용 없음 — 스레드 충돌 없이 각 export() 호출마다 새 인스턴스 할당</li>
 *   <li>대용량 배치도 단일 JSON 문자열로 직렬화: 1만 건 * 평균 500B = ~5MB 상한 고려</li>
 *   <li>shutdown 이후 export()는 즉시 완료된 Void Future 반환</li>
 * </ul>
 */
public final class OtlpHttpLogExporter implements DataExporter<LogRecord> {

    private static final String PATH = "/v1/logs";

    // ---- 내부 상태 ----
    private final URI endpoint;
    private final String serviceName;
    private final OtlpHttpSender sender;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // 관측가능성: 내부 파이프라인 헬스 카운터
    private final AtomicLong exportedCount  = new AtomicLong(0);
    private final AtomicLong droppedCount   = new AtomicLong(0);
    private final AtomicLong failedBatches  = new AtomicLong(0);

    // ---- 생성자 ----

    /**
     * 환경변수/시스템 프로퍼티에서 설정을 읽어 기본 인스턴스를 생성한다.
     */
    public OtlpHttpLogExporter() {
        this(
            resolveEndpoint(),
            resolveServiceName(),
            OtlpHttpSender.create()
        );
    }

    /**
     * 테스트 또는 커스텀 설정 주입용 생성자.
     *
     * @param endpointBase 기본 URL (예: http://localhost:4318)
     * @param serviceName  service.name 리소스 속성 값
     * @param sender       공유 또는 전용 OtlpHttpSender 인스턴스
     */
    public OtlpHttpLogExporter(String endpointBase, String serviceName, OtlpHttpSender sender) {
        // 경로 중복 방지: 이미 /v1/logs가 붙어 있으면 그대로 사용
        String base = endpointBase.endsWith(PATH)
                ? endpointBase
                : endpointBase.replaceAll("/+$", "") + PATH;
        this.endpoint    = URI.create(base);
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    // ---- DataExporter<LogRecord> 구현 ----

    /**
     * 로그 레코드 배치를 OTLP JSON으로 직렬화하여 전송한다.
     *
     * <p>export()는 I/O를 포함하므로 호출자가 별도 스레드(LogBatchProcessor)에서 호출해야 한다.
     * 내부적으로 CompletableFuture.completedFuture(null)을 반환해 인터페이스 계약을 만족한다.
     */
    @Override
    public CompletableFuture<Void> export(Collection<LogRecord> logs) {
        if (isShutdown.get() || logs == null || logs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        String json = toJson(logs, serviceName);
        SendResult result = sender.send(endpoint, json);

        if (result == SendResult.SUCCESS) {
            exportedCount.addAndGet(logs.size());
        } else {
            droppedCount.addAndGet(logs.size());
            failedBatches.incrementAndGet();
            AgentLogger.warn("OtlpHttpLogExporter: 배치 전송 실패 logs=" + logs.size()
                    + " dropped_total=" + droppedCount.get());
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            AgentLogger.info("OtlpHttpLogExporter shutdown: exported=" + exportedCount.get()
                    + " dropped=" + droppedCount.get()
                    + " failed_batches=" + failedBatches.get());
            // sender는 공유 인스턴스일 수 있으므로 여기서는 shutdown하지 않는다.
            // 소유권이 명확한 경우 OtlpHttpSender.shutdown()을 별도로 호출할 것.
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- JSON 직렬화 ----

    /**
     * LogRecord 컬렉션을 OTLP ExportLogsServiceRequest JSON으로 직렬화한다.
     *
     * <p>패키지-프라이빗: 단위 테스트에서 직접 검증 가능.
     */
    static String toJson(Collection<LogRecord> logs, String serviceName) {
        // 예상 용량: 레코드당 약 400B 예상 → 초기 할당으로 재할당 최소화
        StringBuilder sb = new StringBuilder(logs.size() * 400 + 256);

        sb.append("{\"resourceLogs\":[{\"resource\":{\"attributes\":[");
        appendStringAttr(sb, "service.name", serviceName);
        sb.append(",");
        appendStringAttr(sb, "telemetry.sdk.name", "javi-agent");
        sb.append("]},\"scopeLogs\":[{\"scope\":{\"name\":\"javi-log\",\"version\":\"1.0.0\"},");
        sb.append("\"logRecords\":[");

        boolean firstRecord = true;
        for (LogRecord log : logs) {
            if (log == null) continue;
            if (!firstRecord) sb.append(",");
            firstRecord = false;
            appendLogRecord(sb, log);
        }

        sb.append("]}]}]}");
        return sb.toString();
    }

    private static void appendLogRecord(StringBuilder sb, LogRecord log) {
        sb.append("{");

        // timeUnixNano: OTLP 스펙상 string (int64 오버플로우 방지)
        if (log.getTimestamp() != null) {
            long nanos = log.getTimestamp().getEpochSecond() * 1_000_000_000L
                       + log.getTimestamp().getNano();
            sb.append("\"timeUnixNano\":\"").append(nanos).append("\",");
        } else {
            sb.append("\"timeUnixNano\":\"0\",");
        }

        // severityNumber + severityText
        String sev = log.getSeverity() != null ? log.getSeverity().toUpperCase() : "UNSPECIFIED";
        sb.append("\"severityNumber\":").append(severityToNumber(sev)).append(",");
        sb.append("\"severityText\":\"").append(escapeJson(log.getSeverity())).append("\",");

        // body
        sb.append("\"body\":{\"stringValue\":\"").append(escapeJson(log.getBody())).append("\"},");

        // traceId / spanId — OTLP은 hex 문자열 그대로 사용
        if (log.getTraceId() != null && !log.getTraceId().isEmpty()) {
            sb.append("\"traceId\":\"").append(log.getTraceId()).append("\",");
        }
        if (log.getSpanId() != null && !log.getSpanId().isEmpty()) {
            sb.append("\"spanId\":\"").append(log.getSpanId()).append("\",");
        }

        // attributes: loggerName을 포함한 모든 속성
        sb.append("\"attributes\":[");
        boolean firstAttr = true;

        if (log.getLoggerName() != null) {
            appendStringAttr(sb, "logger.name", log.getLoggerName());
            firstAttr = false;
        }

        Map<String, String> attrs = log.getAttributes();
        if (attrs != null) {
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                if (!firstAttr) sb.append(",");
                firstAttr = false;
                appendStringAttr(sb, entry.getKey(), entry.getValue());
            }
        }
        sb.append("]");

        sb.append("}");
    }

    // ---- 헬퍼 ----

    /**
     * OTel SeverityNumber 매핑 (opentelemetry-proto/common/v1/common.proto).
     *
     * <pre>
     * TRACE=1-4, DEBUG=5-8, INFO=9-12, WARN=13-16, ERROR=17-20, FATAL=21-24
     * </pre>
     */
    private static int severityToNumber(String severity) {
        if (severity == null) return 0; // SEVERITY_NUMBER_UNSPECIFIED
        switch (severity) {
            case "TRACE": return 1;
            case "DEBUG": return 5;
            case "INFO":  return 9;
            case "WARN":  case "WARNING": return 13;
            case "ERROR": return 17;
            case "FATAL": return 21;
            default:      return 0;
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
        // 순서 중요: 백슬래시를 먼저 치환
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
