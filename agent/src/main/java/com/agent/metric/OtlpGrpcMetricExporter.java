package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.common.grpc.GrpcSender;
import com.agent.common.grpc.GrpcSender.SendResult;
import com.agent.common.grpc.ProtoEncoder;
import com.agent.common.ResourceInfo;
import com.agent.logs.AgentLogger;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricData를 OTLP/gRPC protobuf 포맷으로 OTel Collector에 전송하는 Exporter.
 *
 * <p>gRPC 서비스 경로:
 * {@code /opentelemetry.proto.collector.metrics.v1.MetricsService/Export}
 *
 * <p>Metric proto field numbers (opentelemetry-proto/metrics/v1/metrics.proto):
 * <ul>
 *   <li>Metric.name = 1, description = 2, unit = 3</li>
 *   <li>Metric.gauge = 5, sum = 7, histogram = 9 (oneof)</li>
 *   <li>NumberDataPoint.attributes = 7, time_unix_nano = 3, as_double = 4</li>
 *   <li>Sum.aggregation_temporality = 2 (CUMULATIVE=2), is_monotonic = 3</li>
 *   <li>HistogramDataPoint.attributes = 9, time_unix_nano = 3, count = 4, sum = 5</li>
 * </ul>
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_GRPC_ENDPOINT / javi.grpc.endpoint (기본: http://localhost:4317)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name (기본: javi-service)</li>
 * </ul>
 */
public final class OtlpGrpcMetricExporter implements DataExporter<MetricData> {

    private static final String GRPC_PATH = "/v1/metrics";

    // OTLP aggregationTemporality: CUMULATIVE = 2
    private static final int TEMPORALITY_CUMULATIVE = 2;

    // ---- Metric proto field numbers ----
    private static final int FN_METRIC_NAME        = 1;
    private static final int FN_METRIC_DESCRIPTION = 2;
    private static final int FN_METRIC_UNIT        = 3;
    private static final int FN_METRIC_GAUGE       = 5;  // oneof data
    private static final int FN_METRIC_SUM         = 7;
    private static final int FN_METRIC_HISTOGRAM   = 9;

    // Gauge field numbers
    private static final int FN_GAUGE_DATA_POINTS  = 1;

    // Sum field numbers
    private static final int FN_SUM_DATA_POINTS    = 1;
    private static final int FN_SUM_TEMPORALITY    = 2;
    private static final int FN_SUM_IS_MONOTONIC   = 3;

    // NumberDataPoint field numbers
    private static final int FN_NDP_ATTRS          = 7;
    private static final int FN_NDP_TIME_NS        = 3;
    private static final int FN_NDP_AS_DOUBLE      = 4;

    // Histogram field numbers
    private static final int FN_HIST_DATA_POINTS   = 1;
    private static final int FN_HIST_TEMPORALITY   = 2;

    // HistogramDataPoint field numbers
    private static final int FN_HDP_ATTRS          = 9;
    private static final int FN_HDP_TIME_NS        = 3;
    private static final int FN_HDP_COUNT          = 4;  // uint64
    private static final int FN_HDP_SUM            = 5;  // optional double
    private static final int FN_HDP_BUCKET_COUNTS  = 6;  // repeated uint64 (packed)
    private static final int FN_HDP_EXPLICIT_BOUNDS= 7;  // repeated double  (packed)
    private static final int FN_HDP_EXEMPLARS      = 8;  // repeated Exemplar
    private static final int FN_HDP_MIN            = 11; // optional double
    private static final int FN_HDP_MAX            = 12; // optional double

    // Exemplar field numbers (opentelemetry-proto/metrics/v1/metrics.proto Exemplar)
    private static final int FN_EX_FILTERED_ATTRS  = 7;  // repeated KeyValue
    private static final int FN_EX_TIME_NS         = 2;  // fixed64
    private static final int FN_EX_AS_DOUBLE       = 3;  // double (oneof value)
    private static final int FN_EX_SPAN_ID         = 4;  // bytes (8 bytes)
    private static final int FN_EX_TRACE_ID        = 5;  // bytes (16 bytes)

    // Top-level wrapper field numbers
    private static final int FN_RESOURCE_METRICS   = 1;  // ExportMetricsServiceRequest
    private static final int FN_RM_RESOURCE        = 1;  // ResourceMetrics.resource
    private static final int FN_RM_SCOPE_METRICS   = 2;  // ResourceMetrics.scope_metrics
    private static final int FN_SM_SCOPE           = 1;  // ScopeMetrics.scope
    private static final int FN_SM_METRICS         = 2;  // ScopeMetrics.metrics
    private static final int FN_SCOPE_NAME         = 1;
    private static final int FN_SCOPE_VERSION      = 2;
    private static final int FN_RESOURCE_ATTRS     = 1;

    // KeyValue / AnyValue
    private static final int FN_KV_KEY    = 1;
    private static final int FN_KV_VALUE  = 2;
    private static final int FN_AV_STRING = 1;

    // ---- 내부 상태 ----
    private final GrpcSender sender;
    private final String serviceName;
    private final AtomicBoolean isShutdown    = new AtomicBoolean(false);
    private final AtomicLong exportedPoints   = new AtomicLong(0);
    private final AtomicLong droppedPoints    = new AtomicLong(0);
    private final AtomicLong failedBatches    = new AtomicLong(0);

    // ---- 생성자 ----

    public OtlpGrpcMetricExporter() {
        this(resolveServiceName(), GrpcSender.create());
    }

    public OtlpGrpcMetricExporter(String serviceName, GrpcSender sender) {
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    // ---- DataExporter<MetricData> 구현 ----

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> metrics) {
        if (isShutdown.get() || metrics == null || metrics.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        int totalPoints = 0;
        for (MetricData m : metrics) {
            if (m != null && m.getPoints() != null) totalPoints += m.getPoints().size();
        }

        try {
            byte[] protoBytes = encodeExportRequest(metrics, serviceName);
            SendResult result = sender.send(GRPC_PATH, protoBytes);

            if (result == SendResult.SUCCESS) {
                exportedPoints.addAndGet(totalPoints);
            } else {
                droppedPoints.addAndGet(totalPoints);
                failedBatches.incrementAndGet();
                AgentLogger.warn("OtlpGrpcMetricExporter: 배치 전송 실패 metrics=" + metrics.size()
                        + " points=" + totalPoints + " dropped_total=" + droppedPoints.get());
            }
        } catch (Exception e) {
            AgentLogger.warn("OtlpGrpcMetricExporter: 인코딩 오류 — " + e.getMessage());
            droppedPoints.addAndGet(totalPoints);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            AgentLogger.info("OtlpGrpcMetricExporter shutdown: exported_points=" + exportedPoints.get()
                    + " dropped_points=" + droppedPoints.get()
                    + " failed_batches=" + failedBatches.get());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- Protobuf 인코딩 ----

    static byte[] encodeExportRequest(Collection<MetricData> metrics, String serviceName) {
        byte[] resourceBytes = encodeResource(serviceName);

        ByteArrayOutputStream metricsOut = new ByteArrayOutputStream(metrics.size() * 200);
        for (MetricData metric : metrics) {
            if (metric == null) continue;
            boolean hasPoints = metric.getPoints() != null && !metric.getPoints().isEmpty();
            boolean hasHistPts = metric.getHistogramPoints() != null && !metric.getHistogramPoints().isEmpty();
            if (!hasPoints && !hasHistPts) continue;
            byte[] metricBytes = encodeMetric(metric);
            ProtoEncoder.writeMessage(metricsOut, FN_SM_METRICS, metricBytes);
        }

        ByteArrayOutputStream scopeOut = new ByteArrayOutputStream(32);
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_NAME, "javi-metric");
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_VERSION, "1.0.0");

        ByteArrayOutputStream scopeMetricsOut = new ByteArrayOutputStream(64 + metricsOut.size());
        ProtoEncoder.writeMessage(scopeMetricsOut, FN_SM_SCOPE, scopeOut.toByteArray());
        byte[] mBytes = metricsOut.toByteArray();
        scopeMetricsOut.write(mBytes, 0, mBytes.length);

        ByteArrayOutputStream rmOut = new ByteArrayOutputStream(128 + scopeMetricsOut.size());
        ProtoEncoder.writeMessage(rmOut, FN_RM_RESOURCE, resourceBytes);
        ProtoEncoder.writeMessage(rmOut, FN_RM_SCOPE_METRICS, scopeMetricsOut.toByteArray());

        ByteArrayOutputStream requestOut = new ByteArrayOutputStream(rmOut.size() + 4);
        ProtoEncoder.writeMessage(requestOut, FN_RESOURCE_METRICS, rmOut.toByteArray());
        return requestOut.toByteArray();
    }

    private static byte[] encodeResource(String serviceName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        // ResourceInfo에서 동적으로 모든 속성 가져오기
        for (Map.Entry<String, String> entry : ResourceInfo.getAttributes().entrySet()) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV(entry.getKey(), entry.getValue()));
        }

        // service.name 보장
        if (!ResourceInfo.getAttributes().containsKey("service.name")) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("service.name", serviceName));
        }

        return out.toByteArray();
    }

    private static byte[] encodeMetric(MetricData metric) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        ProtoEncoder.writeString(out, FN_METRIC_NAME, metric.getName());
        if (metric.getDescription() != null && !metric.getDescription().isEmpty()) {
            ProtoEncoder.writeString(out, FN_METRIC_DESCRIPTION, metric.getDescription());
        }
        if (metric.getUnit() != null && !metric.getUnit().isEmpty()) {
            ProtoEncoder.writeString(out, FN_METRIC_UNIT, metric.getUnit());
        }

        MetricData.MetricType type = metric.getType();
        if (type == null) type = MetricData.MetricType.GAUGE;

        switch (type) {
            case GAUGE:
                ProtoEncoder.writeMessage(out, FN_METRIC_GAUGE, encodeGauge(metric.getPoints()));
                break;
            case SUM:
                ProtoEncoder.writeMessage(out, FN_METRIC_SUM, encodeSum(metric.getPoints()));
                break;
            case HISTOGRAM:
                ProtoEncoder.writeMessage(out, FN_METRIC_HISTOGRAM, encodeHistogram(metric));
                break;
            default:
                ProtoEncoder.writeMessage(out, FN_METRIC_GAUGE, encodeGauge(metric.getPoints()));
                break;
        }
        return out.toByteArray();
    }

    private static byte[] encodeGauge(Collection<MetricData.Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 64);
        for (MetricData.Point point : points) {
            if (point == null) continue;
            ProtoEncoder.writeMessage(out, FN_GAUGE_DATA_POINTS, encodeNumberDataPoint(point));
        }
        return out.toByteArray();
    }

    private static byte[] encodeSum(Collection<MetricData.Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 64 + 8);
        for (MetricData.Point point : points) {
            if (point == null) continue;
            ProtoEncoder.writeMessage(out, FN_SUM_DATA_POINTS, encodeNumberDataPoint(point));
        }
        // aggregation_temporality = CUMULATIVE(2), is_monotonic = true(1)
        ProtoEncoder.writeVarint32(out, FN_SUM_TEMPORALITY, TEMPORALITY_CUMULATIVE);
        ProtoEncoder.writeVarint32(out, FN_SUM_IS_MONOTONIC, 1);
        return out.toByteArray();
    }

    private static byte[] encodeHistogram(MetricData metric) {
        Collection<MetricData.HistogramPoint> hpts = metric.getHistogramPoints();
        ByteArrayOutputStream out;
        if (hpts != null && !hpts.isEmpty()) {
            // ExplicitBucketHistogram — 버킷 데이터 포함
            out = new ByteArrayOutputStream(hpts.size() * 200 + 8);
            for (MetricData.HistogramPoint hp : hpts) {
                if (hp == null) continue;
                ProtoEncoder.writeMessage(out, FN_HIST_DATA_POINTS, encodeHistogramDataPointFull(hp));
            }
        } else {
            // 기존 Point 기반 히스토그램 (하위 호환)
            Collection<MetricData.Point> pts = metric.getPoints();
            out = new ByteArrayOutputStream(pts.size() * 80 + 8);
            for (MetricData.Point point : pts) {
                if (point == null) continue;
                ProtoEncoder.writeMessage(out, FN_HIST_DATA_POINTS, encodeHistogramDataPointSimple(point));
            }
        }
        ProtoEncoder.writeVarint32(out, FN_HIST_TEMPORALITY, TEMPORALITY_CUMULATIVE);
        return out.toByteArray();
    }

    private static byte[] encodeHistogramDataPointFull(MetricData.HistogramPoint hp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        encodePointAttributes(out, FN_HDP_ATTRS, hp.getAttributes());
        if (hp.getTimestamp() != null) {
            long nanos = hp.getTimestamp().getEpochSecond() * 1_000_000_000L
                       + hp.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_HDP_TIME_NS, nanos);
        }
        // count (uint64 varint)
        ProtoEncoder.writeTag(out, FN_HDP_COUNT, ProtoEncoder.WIRE_VARINT);
        ProtoEncoder.writeRawVarint64(out, hp.getCount());
        // sum (optional double)
        ProtoEncoder.writeDoubleField(out, FN_HDP_SUM, hp.getSum());
        // bucket_counts: packed repeated uint64
        ProtoEncoder.writePackedUint64(out, FN_HDP_BUCKET_COUNTS, hp.getBucketCounts());
        // explicit_bounds: packed repeated double
        ProtoEncoder.writePackedDouble(out, FN_HDP_EXPLICIT_BOUNDS, hp.getBoundaries());
        // exemplars: repeated Exemplar (field 8) — Metric-Trace 연결
        List<Exemplar> exemplars = hp.getExemplars();
        if (exemplars != null && !exemplars.isEmpty()) {
            for (Exemplar ex : exemplars) {
                ProtoEncoder.writeMessage(out, FN_HDP_EXEMPLARS, encodeExemplar(ex));
            }
        }
        // min / max (optional double — OTel spec field 11/12)
        if (hp.getMin() > 0) ProtoEncoder.writeDoubleField(out, FN_HDP_MIN, hp.getMin());
        if (hp.getMax() > 0) ProtoEncoder.writeDoubleField(out, FN_HDP_MAX, hp.getMax());
        return out.toByteArray();
    }

    /**
     * Exemplar 프로토 인코딩.
     *
     * <p>OTLP Exemplar proto field numbers:
     * <pre>
     *   filtered_attributes = 7 (repeated KeyValue)
     *   time_unix_nano       = 2 (fixed64)
     *   as_double            = 3 (double, oneof value)
     *   span_id              = 4 (bytes, 8 bytes)
     *   trace_id             = 5 (bytes, 16 bytes)
     * </pre>
     */
    private static byte[] encodeExemplar(Exemplar ex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        // filtered_attributes (선택적 속성)
        encodePointAttributes(out, FN_EX_FILTERED_ATTRS, ex.getFilteredAttributes());
        // time_unix_nano (fixed64)
        if (ex.getTimeUnixNano() != 0L) {
            ProtoEncoder.writeFixed64Field(out, FN_EX_TIME_NS, ex.getTimeUnixNano());
        }
        // as_double: 측정값
        ProtoEncoder.writeDoubleField(out, FN_EX_AS_DOUBLE, ex.getValue());
        // span_id: 8 bytes
        if (ex.getSpanId() != null && !ex.getSpanId().isEmpty()) {
            byte[] spanIdBytes = ProtoEncoder.hexToBytes(ex.getSpanId());
            if (spanIdBytes != null) ProtoEncoder.writeBytes(out, FN_EX_SPAN_ID, spanIdBytes);
        }
        // trace_id: 16 bytes
        if (ex.getTraceId() != null && !ex.getTraceId().isEmpty()) {
            byte[] traceIdBytes = ProtoEncoder.hexToBytes(ex.getTraceId());
            if (traceIdBytes != null) ProtoEncoder.writeBytes(out, FN_EX_TRACE_ID, traceIdBytes);
        }
        return out.toByteArray();
    }

    private static byte[] encodeNumberDataPoint(MetricData.Point point) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        encodePointAttributes(out, FN_NDP_ATTRS, point.getAttributes());
        if (point.getTimestamp() != null) {
            long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L
                       + point.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_NDP_TIME_NS, nanos);
        }
        ProtoEncoder.writeDoubleField(out, FN_NDP_AS_DOUBLE, point.getValue());
        return out.toByteArray();
    }

    private static byte[] encodeHistogramDataPointSimple(MetricData.Point point) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(80);
        encodePointAttributes(out, FN_HDP_ATTRS, point.getAttributes());
        if (point.getTimestamp() != null) {
            long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L
                       + point.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_HDP_TIME_NS, nanos);
        }
        ProtoEncoder.writeTag(out, FN_HDP_COUNT, ProtoEncoder.WIRE_VARINT);
        ProtoEncoder.writeRawVarint64(out, 1L);
        ProtoEncoder.writeDoubleField(out, FN_HDP_SUM, point.getValue());
        return out.toByteArray();
    }

    private static void encodePointAttributes(ByteArrayOutputStream out, int fieldNumber,
                                              Map<String, String> attrs) {
        if (attrs == null) return;
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            ProtoEncoder.writeMessage(out, fieldNumber, encodeStringKV(entry.getKey(), entry.getValue()));
        }
    }

    private static byte[] encodeStringKV(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + (value != null ? value.length() : 0) + 8);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ByteArrayOutputStream av = new ByteArrayOutputStream((value != null ? value.length() : 0) + 4);
        ProtoEncoder.writeString(av, FN_AV_STRING, value != null ? value : "");
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
        return out.toByteArray();
    }

    private static String resolveServiceName() {
        String val = System.getenv("JAVI_SERVICE_NAME");
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty("javi.service.name");
        if (val != null && !val.isEmpty()) return val;
        return "javi-service";
    }
}
