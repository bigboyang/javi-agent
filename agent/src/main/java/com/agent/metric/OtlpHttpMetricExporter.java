package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.common.OtlpHttpSender;
import com.agent.common.OtlpHttpSender.SendResult;
import com.agent.logs.AgentLogger;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricData를 OTLP/HTTP JSON 포맷으로 /v1/metrics 엔드포인트에 전송하는 Exporter.
 *
 * <p>OTLP Metrics 스펙 (opentelemetry-proto/metrics/v1/metrics.proto):
 * <pre>
 * ExportMetricsServiceRequest
 *   └─ resourceMetrics[]
 *        ├─ resource { attributes[] }
 *        └─ scopeMetrics[]
 *             ├─ scope { name, version }
 *             └─ metrics[]
 *                  ├─ name (string)
 *                  ├─ description (string)
 *                  ├─ unit (string)
 *                  └─ [gauge|sum|histogram]
 *                       └─ dataPoints[]
 *                            ├─ asDouble (number)
 *                            ├─ timeUnixNano (string)
 *                            └─ attributes[]
 * </pre>
 *
 * <p>MetricType 매핑:
 * <ul>
 *   <li>GAUGE → gauge.dataPoints[]</li>
 *   <li>SUM   → sum.dataPoints[] (aggregationTemporality=CUMULATIVE, isMonotonic=true)</li>
 *   <li>HISTOGRAM → histogram.dataPoints[] — Point.value를 sum으로, count=1 처리</li>
 * </ul>
 *
 * <p>Cardinality 안전: 각 Point의 attributes는 Map<String,String>으로 고정.
 * 키 개수를 스펙 권장 20개 이하로 유지할 책임은 상위 계층(MetricRegistry)에 있다.
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_COLLECTOR_ENDPOINT / javi.collector.endpoint (기본: http://localhost:4318)</li>
 *   <li>JAVI_COLLECTOR_TIMEOUT_MS / javi.collector.timeout.ms (기본: 10000)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name (기본: javi-service)</li>
 * </ul>
 */
public final class OtlpHttpMetricExporter implements DataExporter<MetricData> {

    private static final String PATH = "/v1/metrics";

    // OTLP aggregationTemporality: DELTA=1, CUMULATIVE=2
    private static final int TEMPORALITY_CUMULATIVE = 2;

    // ---- 내부 상태 ----
    private final URI endpoint;
    private final String serviceName;
    private final OtlpHttpSender sender;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // 파이프라인 헬스 카운터
    private final AtomicLong exportedPoints = new AtomicLong(0);
    private final AtomicLong droppedPoints  = new AtomicLong(0);
    private final AtomicLong failedBatches  = new AtomicLong(0);

    // ---- 생성자 ----

    /**
     * 환경변수/시스템 프로퍼티에서 설정을 읽어 기본 인스턴스를 생성한다.
     */
    public OtlpHttpMetricExporter() {
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
    public OtlpHttpMetricExporter(String endpointBase, String serviceName, OtlpHttpSender sender) {
        String base = endpointBase.endsWith(PATH)
                ? endpointBase
                : endpointBase.replaceAll("/+$", "") + PATH;
        this.endpoint    = URI.create(base);
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    // ---- DataExporter<MetricData> 구현 ----

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> metrics) {
        if (isShutdown.get() || metrics == null || metrics.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // 포인트 수 집계 (드롭 카운터용)
        int totalPoints = 0;
        for (MetricData m : metrics) {
            if (m != null && m.getPoints() != null) {
                totalPoints += m.getPoints().size();
            }
        }

        String json = toJson(metrics, serviceName);
        SendResult result = sender.send(endpoint, json);

        if (result == SendResult.SUCCESS) {
            exportedPoints.addAndGet(totalPoints);
        } else {
            droppedPoints.addAndGet(totalPoints);
            failedBatches.incrementAndGet();
            AgentLogger.warn("OtlpHttpMetricExporter: 배치 전송 실패 metrics=" + metrics.size()
                    + " points=" + totalPoints
                    + " dropped_total=" + droppedPoints.get());
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            AgentLogger.info("OtlpHttpMetricExporter shutdown: exported_points=" + exportedPoints.get()
                    + " dropped_points=" + droppedPoints.get()
                    + " failed_batches=" + failedBatches.get());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- JSON 직렬화 ----

    /**
     * MetricData 컬렉션을 OTLP ExportMetricsServiceRequest JSON으로 직렬화한다.
     *
     * <p>패키지-프라이빗: 단위 테스트에서 직접 검증 가능.
     */
    static String toJson(Collection<MetricData> metrics, String serviceName) {
        // 예상 용량: 메트릭당 약 300B * 포인트 수
        int estimatedSize = metrics.size() * 300 + 256;
        StringBuilder sb = new StringBuilder(estimatedSize);

        sb.append("{\"resourceMetrics\":[{\"resource\":{\"attributes\":[");
        appendStringAttr(sb, "service.name", serviceName);
        sb.append(",");
        appendStringAttr(sb, "telemetry.sdk.name", "javi-agent");
        sb.append("]},\"scopeMetrics\":[{\"scope\":{\"name\":\"javi-metric\",\"version\":\"1.0.0\"},");
        sb.append("\"metrics\":[");

        boolean firstMetric = true;
        for (MetricData metric : metrics) {
            if (metric == null) continue;
            if (metric.getPoints() == null || metric.getPoints().isEmpty()) continue;

            if (!firstMetric) sb.append(",");
            firstMetric = false;
            appendMetric(sb, metric);
        }

        sb.append("]}]}]}");
        return sb.toString();
    }

    private static void appendMetric(StringBuilder sb, MetricData metric) {
        sb.append("{");
        sb.append("\"name\":\"").append(escapeJson(metric.getName())).append("\",");

        // description / unit — MetricData가 getter를 노출하지 않을 경우 빈 문자열로 안전 처리
        sb.append("\"description\":\"\",");
        sb.append("\"unit\":\"\",");

        // MetricType에 따라 다른 JSON 키 사용
        MetricData.MetricType type = metric.getType();
        if (type == null) type = MetricData.MetricType.GAUGE;

        switch (type) {
            case GAUGE:
                sb.append("\"gauge\":{\"dataPoints\":[");
                appendNumberDataPoints(sb, metric.getPoints());
                sb.append("]}");
                break;

            case SUM:
                // SUM은 단조 증가 Counter에 대응. CUMULATIVE temporality 사용.
                sb.append("\"sum\":{");
                sb.append("\"aggregationTemporality\":").append(TEMPORALITY_CUMULATIVE).append(",");
                sb.append("\"isMonotonic\":true,");
                sb.append("\"dataPoints\":[");
                appendNumberDataPoints(sb, metric.getPoints());
                sb.append("]}");
                break;

            case HISTOGRAM:
                // 단순화: Point.value를 sum으로 사용, count=1, bounds/buckets 생략
                sb.append("\"histogram\":{");
                sb.append("\"aggregationTemporality\":").append(TEMPORALITY_CUMULATIVE).append(",");
                sb.append("\"dataPoints\":[");
                appendHistogramDataPoints(sb, metric.getPoints());
                sb.append("]}");
                break;

            default:
                // 알 수 없는 타입: GAUGE로 fallback
                sb.append("\"gauge\":{\"dataPoints\":[");
                appendNumberDataPoints(sb, metric.getPoints());
                sb.append("]}");
                break;
        }

        sb.append("}");
    }

    /** GAUGE / SUM 공용 NumberDataPoint 직렬화. */
    private static void appendNumberDataPoints(StringBuilder sb, Collection<MetricData.Point> points) {
        boolean first = true;
        for (MetricData.Point point : points) {
            if (point == null) continue;
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            appendPointAttributes(sb, point.getAttributes());
            sb.append(",");

            // timeUnixNano: OTLP은 string (int64)
            if (point.getTimestamp() != null) {
                long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L
                           + point.getTimestamp().getNano();
                sb.append("\"timeUnixNano\":\"").append(nanos).append("\",");
            } else {
                sb.append("\"timeUnixNano\":\"0\",");
            }

            // OTLP NumberDataPoint: asDouble 또는 asInt
            // double 범위 이내이면 asDouble 사용
            sb.append("\"asDouble\":").append(point.getValue());
            sb.append("}");
        }
    }

    /** HistogramDataPoint 직렬화 — 단순 버전 (count=1, sum=value, 버킷 없음). */
    private static void appendHistogramDataPoints(StringBuilder sb, Collection<MetricData.Point> points) {
        boolean first = true;
        for (MetricData.Point point : points) {
            if (point == null) continue;
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            appendPointAttributes(sb, point.getAttributes());
            sb.append(",");

            if (point.getTimestamp() != null) {
                long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L
                           + point.getTimestamp().getNano();
                sb.append("\"timeUnixNano\":\"").append(nanos).append("\",");
            } else {
                sb.append("\"timeUnixNano\":\"0\",");
            }

            sb.append("\"count\":\"1\",");
            sb.append("\"sum\":").append(point.getValue()).append(",");
            // explicitBounds, bucketCounts 없이 전송하면 Collector가 graceful하게 처리
            sb.append("\"explicitBounds\":[],");
            sb.append("\"bucketCounts\":[\"1\"]");
            sb.append("}");
        }
    }

    private static void appendPointAttributes(StringBuilder sb, Map<String, String> attrs) {
        sb.append("\"attributes\":[");
        if (attrs != null && !attrs.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                appendStringAttr(sb, entry.getKey(), entry.getValue());
            }
        }
        sb.append("]");
    }

    // ---- 헬퍼 ----

    private static void appendStringAttr(StringBuilder sb, String key, String value) {
        sb.append("{\"key\":\"").append(escapeJson(key))
          .append("\",\"value\":{\"stringValue\":\"").append(escapeJson(value)).append("\"}}");
    }

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
