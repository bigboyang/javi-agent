package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.common.OtlpHttpProtobufSender;
import com.agent.common.OtlpHttpProtobufSender.SendResult;
import com.agent.common.ProtoEncoder;
import com.agent.common.ResourceInfo;
import com.agent.logs.AgentLogger;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricData를 OTLP/HTTP Protobuf 포맷으로 OTel Collector에 전송하는 Exporter.
 */
public final class OtlpHttpMetricExporter implements DataExporter<MetricData> {

    private static final String METRIC_PATH = "/v1/metrics";
    private static final int TEMPORALITY_CUMULATIVE = 2;

    // ---- Metric proto field numbers ----
    private static final int FN_METRIC_NAME        = 1;
    private static final int FN_METRIC_DESCRIPTION = 2;
    private static final int FN_METRIC_UNIT        = 3;
    private static final int FN_METRIC_GAUGE       = 5;
    private static final int FN_METRIC_SUM         = 7;
    private static final int FN_METRIC_HISTOGRAM   = 9;
    private static final int FN_GAUGE_DATA_POINTS  = 1;
    private static final int FN_SUM_DATA_POINTS    = 1;
    private static final int FN_SUM_TEMPORALITY    = 2;
    private static final int FN_SUM_IS_MONOTONIC   = 3;
    private static final int FN_NDP_ATTRS          = 7;
    private static final int FN_NDP_START_TIME_NS  = 2;
    private static final int FN_NDP_TIME_NS        = 3;
    private static final int FN_NDP_AS_DOUBLE      = 4;
    private static final int FN_HIST_DATA_POINTS   = 1;
    private static final int FN_HIST_TEMPORALITY   = 2;
    private static final int FN_HDP_ATTRS          = 9;
    private static final int FN_HDP_START_TIME_NS  = 2;
    private static final int FN_HDP_TIME_NS        = 3;
    private static final int FN_HDP_COUNT          = 4;
    private static final int FN_HDP_SUM            = 5;
    private static final int FN_HDP_BUCKET_COUNTS  = 6;
    private static final int FN_HDP_EXPLICIT_BOUNDS= 7;
    private static final int FN_HDP_EXEMPLARS      = 8;
    private static final int FN_HDP_MIN            = 11;
    private static final int FN_HDP_MAX            = 12;

    private static final int FN_EX_FILTERED_ATTRS  = 7;
    private static final int FN_EX_TIME_NS         = 2;
    private static final int FN_EX_AS_DOUBLE       = 3;
    private static final int FN_EX_SPAN_ID         = 4;
    private static final int FN_EX_TRACE_ID        = 5;

    private static final int FN_RESOURCE_METRICS   = 1;
    private static final int FN_RM_RESOURCE        = 1;
    private static final int FN_RM_SCOPE_METRICS   = 2;
    private static final int FN_SM_SCOPE           = 1;
    private static final int FN_SM_METRICS         = 2;
    private static final int FN_SCOPE_NAME         = 1;
    private static final int FN_SCOPE_VERSION      = 2;
    private static final int FN_RESOURCE_ATTRS     = 1;

    private static final int FN_KV_KEY    = 1;
    private static final int FN_KV_VALUE  = 2;
    private static final int FN_AV_STRING = 1;

    private static final long PROCESS_START_NS;
    static {
        PROCESS_START_NS = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime() * 1_000_000L;
    }

    private final OtlpHttpProtobufSender sender;
    private final String serviceName;
    private final AtomicBoolean isShutdown    = new AtomicBoolean(false);
    private final AtomicLong exportedPoints   = new AtomicLong(0);
    private final AtomicLong droppedPoints    = new AtomicLong(0);
    private final AtomicLong failedBatches    = new AtomicLong(0);

    public OtlpHttpMetricExporter() {
        this(resolveServiceName(), OtlpHttpProtobufSender.create());
    }

    public OtlpHttpMetricExporter(String serviceName, OtlpHttpProtobufSender sender) {
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> metrics) {
        if (isShutdown.get() || metrics == null || metrics.isEmpty()) return CompletableFuture.completedFuture(null);

        int totalPoints = 0;
        for (MetricData m : metrics) {
            if (m != null && m.getPoints() != null) totalPoints += m.getPoints().size();
        }

        try {
            byte[] protoBytes = encodeExportRequest(metrics, serviceName);
            SendResult result = sender.send(METRIC_PATH, protoBytes);

            if (result == SendResult.SUCCESS) {
                exportedPoints.addAndGet(totalPoints);
            } else {
                droppedPoints.addAndGet(totalPoints);
                failedBatches.incrementAndGet();
            }
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpMetricExporter: 인코딩 오류 — " + e.getMessage());
            droppedPoints.addAndGet(totalPoints);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        isShutdown.set(true);
        return CompletableFuture.completedFuture(null);
    }

    static byte[] encodeExportRequest(Collection<MetricData> metrics, String serviceName) {
        byte[] resourceBytes = encodeResource(serviceName);
        ByteArrayOutputStream metricsOut = new ByteArrayOutputStream(metrics.size() * 200);
        for (MetricData metric : metrics) {
            if (metric == null) continue;
            ProtoEncoder.writeMessage(metricsOut, FN_SM_METRICS, encodeMetric(metric));
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
        for (Map.Entry<String, String> entry : ResourceInfo.getAttributes().entrySet()) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV(entry.getKey(), entry.getValue()));
        }
        if (!ResourceInfo.getAttributes().containsKey("service.name")) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("service.name", serviceName));
        }
        return out.toByteArray();
    }

    private static byte[] encodeMetric(MetricData metric) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        ProtoEncoder.writeString(out, FN_METRIC_NAME, metric.getName());
        if (metric.getDescription() != null && !metric.getDescription().isEmpty()) ProtoEncoder.writeString(out, FN_METRIC_DESCRIPTION, metric.getDescription());
        if (metric.getUnit() != null && !metric.getUnit().isEmpty()) ProtoEncoder.writeString(out, FN_METRIC_UNIT, metric.getUnit());

        MetricData.MetricType type = metric.getType();
        if (type == null) type = MetricData.MetricType.GAUGE;

        switch (type) {
            case GAUGE:     ProtoEncoder.writeMessage(out, FN_METRIC_GAUGE, encodeGauge(metric.getPoints())); break;
            case SUM:       ProtoEncoder.writeMessage(out, FN_METRIC_SUM, encodeSum(metric.getPoints())); break;
            case HISTOGRAM: ProtoEncoder.writeMessage(out, FN_METRIC_HISTOGRAM, encodeHistogram(metric)); break;
            default:        ProtoEncoder.writeMessage(out, FN_METRIC_GAUGE, encodeGauge(metric.getPoints())); break;
        }
        return out.toByteArray();
    }

    private static byte[] encodeGauge(Collection<MetricData.Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 64);
        for (MetricData.Point point : points) ProtoEncoder.writeMessage(out, FN_GAUGE_DATA_POINTS, encodeNumberDataPoint(point, 0L));
        return out.toByteArray();
    }

    private static byte[] encodeSum(Collection<MetricData.Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 64 + 8);
        for (MetricData.Point point : points) ProtoEncoder.writeMessage(out, FN_SUM_DATA_POINTS, encodeNumberDataPoint(point, PROCESS_START_NS));
        ProtoEncoder.writeVarint32(out, FN_SUM_TEMPORALITY, TEMPORALITY_CUMULATIVE);
        ProtoEncoder.writeVarint32(out, FN_SUM_IS_MONOTONIC, 1);
        return out.toByteArray();
    }

    private static byte[] encodeHistogram(MetricData metric) {
        Collection<MetricData.HistogramPoint> hpts = metric.getHistogramPoints();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        if (hpts != null && !hpts.isEmpty()) {
            for (MetricData.HistogramPoint hp : hpts) ProtoEncoder.writeMessage(out, FN_HIST_DATA_POINTS, encodeHistogramDataPointFull(hp, PROCESS_START_NS));
        } else {
            for (MetricData.Point point : metric.getPoints()) ProtoEncoder.writeMessage(out, FN_HIST_DATA_POINTS, encodeHistogramDataPointSimple(point, PROCESS_START_NS));
        }
        ProtoEncoder.writeVarint32(out, FN_HIST_TEMPORALITY, TEMPORALITY_CUMULATIVE);
        return out.toByteArray();
    }

    private static byte[] encodeHistogramDataPointFull(MetricData.HistogramPoint hp, long startTimeNs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        encodePointAttributes(out, FN_HDP_ATTRS, hp.getAttributes());
        if (startTimeNs > 0) ProtoEncoder.writeFixed64Field(out, FN_HDP_START_TIME_NS, startTimeNs);
        if (hp.getTimestamp() != null) {
            long nanos = hp.getTimestamp().getEpochSecond() * 1_000_000_000L + hp.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_HDP_TIME_NS, nanos);
        }
        ProtoEncoder.writeFixed64Field(out, FN_HDP_COUNT, hp.getCount());
        ProtoEncoder.writeDoubleField(out, FN_HDP_SUM, hp.getSum());
        ProtoEncoder.writePackedFixed64(out, FN_HDP_BUCKET_COUNTS, hp.getBucketCounts());
        ProtoEncoder.writePackedDouble(out, FN_HDP_EXPLICIT_BOUNDS, hp.getBoundaries());
        if (hp.getMin() > 0) ProtoEncoder.writeDoubleField(out, FN_HDP_MIN, hp.getMin());
        if (hp.getMax() > 0) ProtoEncoder.writeDoubleField(out, FN_HDP_MAX, hp.getMax());
        return out.toByteArray();
    }

    private static byte[] encodeNumberDataPoint(MetricData.Point point, long startTimeNs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        encodePointAttributes(out, FN_NDP_ATTRS, point.getAttributes());
        if (startTimeNs > 0) ProtoEncoder.writeFixed64Field(out, FN_NDP_START_TIME_NS, startTimeNs);
        if (point.getTimestamp() != null) {
            long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L + point.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_NDP_TIME_NS, nanos);
        }
        ProtoEncoder.writeDoubleField(out, FN_NDP_AS_DOUBLE, point.getValue());
        return out.toByteArray();
    }

    private static byte[] encodeHistogramDataPointSimple(MetricData.Point point, long startTimeNs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(80);
        encodePointAttributes(out, FN_HDP_ATTRS, point.getAttributes());
        if (startTimeNs > 0) ProtoEncoder.writeFixed64Field(out, FN_HDP_START_TIME_NS, startTimeNs);
        if (point.getTimestamp() != null) {
            long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L + point.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_HDP_TIME_NS, nanos);
        }
        ProtoEncoder.writeFixed64Field(out, FN_HDP_COUNT, 1L);
        ProtoEncoder.writeDoubleField(out, FN_HDP_SUM, point.getValue());
        return out.toByteArray();
    }

    private static void encodePointAttributes(ByteArrayOutputStream out, int fieldNumber, Map<String, String> attrs) {
        if (attrs == null) return;
        for (Map.Entry<String, String> entry : attrs.entrySet()) ProtoEncoder.writeMessage(out, fieldNumber, encodeStringKV(entry.getKey(), entry.getValue()));
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
        return (val != null && !val.isEmpty()) ? val : System.getProperty("javi.service.name", "javi-service");
    }
}
