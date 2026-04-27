package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.common.OtlpHttpProtobufSender;
import com.agent.common.OtlpHttpProtobufSender.SendResult;
import com.agent.common.ProtoEncoder;
import com.agent.common.ResourceInfo;
import com.agent.logs.AgentLogger;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricData를 OTLP/HTTP Protobuf 포맷으로 OTel Collector에 전송하는 Exporter.
 *
 * <p>Temporality 전략:
 * <ul>
 *   <li>SUM (Counter) — CUMULATIVE: 프로세스 시작 이후 누적값. Prometheus 호환.</li>
 *   <li>HISTOGRAM — DELTA: 이전 export 이후 구간별 delta 계산.
 *       내부 MetricRegistry는 누적값을 유지하므로, exporter가 이전 값을 추적해
 *       delta를 계산한다. 이를 통해 Prometheus(누적 스냅샷)와 OTLP(DELTA) 양쪽을
 *       동시에 지원한다.</li>
 * </ul>
 */
public final class OtlpHttpMetricExporter implements DataExporter<MetricData> {

    private static final String METRIC_PATH = "/v1/metrics";
    private static final int TEMPORALITY_DELTA      = 1;
    private static final int TEMPORALITY_CUMULATIVE = 2;

    // ---- Metric proto field numbers ----
    private static final int FN_METRIC_NAME                 = 1;
    private static final int FN_METRIC_DESCRIPTION          = 2;
    private static final int FN_METRIC_UNIT                 = 3;
    private static final int FN_METRIC_GAUGE                = 5;
    private static final int FN_METRIC_SUM                  = 7;
    private static final int FN_METRIC_HISTOGRAM            = 9;
    private static final int FN_METRIC_EXPONENTIAL_HISTOGRAM = 10;

    // ---- ExponentialHistogram proto field numbers ----
    private static final int FN_EH_DATA_POINTS   = 1;
    private static final int FN_EH_TEMPORALITY   = 2;
    private static final int FN_EHP_ATTRS        = 1;
    private static final int FN_EHP_START_TIME   = 2;
    private static final int FN_EHP_TIME         = 3;
    private static final int FN_EHP_COUNT        = 4;   // uint64
    private static final int FN_EHP_SUM          = 5;   // double (optional — omit when absent)
    private static final int FN_EHP_SCALE        = 6;   // sint32 (zigzag; 0도 유효)
    private static final int FN_EHP_ZERO_COUNT   = 7;   // uint64
    private static final int FN_EHP_POSITIVE     = 8;   // Buckets message
    private static final int FN_EHP_NEGATIVE     = 9;   // Buckets message
    private static final int FN_EHP_EXEMPLARS    = 11;
    private static final int FN_EHP_MIN          = 12;
    private static final int FN_EHP_MAX          = 13;
    private static final int FN_BUCKETS_OFFSET   = 1;   // sint32 (zigzag; 0도 유효)
    private static final int FN_BUCKETS_COUNTS   = 2;   // packed uint64
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
    private static final int FN_HDP_COUNT          = 4;  // uint64 → varint
    private static final int FN_HDP_SUM            = 5;
    private static final int FN_HDP_BUCKET_COUNTS  = 6;  // repeated uint64 → packed varint
    private static final int FN_HDP_EXPLICIT_BOUNDS= 7;
    private static final int FN_HDP_EXEMPLARS      = 8;
    private static final int FN_HDP_MIN            = 11;
    private static final int FN_HDP_MAX            = 12;

    // ---- Exemplar proto field numbers ----
    private static final int FN_EX_FILTERED_ATTRS  = 7;
    private static final int FN_EX_TIME_NS         = 2;
    private static final int FN_EX_AS_DOUBLE       = 3;
    private static final int FN_EX_SPAN_ID         = 4;
    private static final int FN_EX_TRACE_ID        = 5;
    private static final int FN_EX_FLAGS           = 8;  // uint32 SpanFlags (SAMPLED=0x01)

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
    private static final int FN_AV_BOOL   = 2;   // AnyValue.bool_value
    private static final int FN_AV_INT    = 3;   // AnyValue.int_value (int64)
    private static final int FN_AV_DOUBLE = 4;   // AnyValue.double_value

    private static final long PROCESS_START_NS;
    static {
        PROCESS_START_NS = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime() * 1_000_000L;
    }

    // ---- DELTA histogram 상태 추적 ----
    // key: "metricName|attrsKey" → 이전 export 시점의 누적값
    // LRU 제한: 동적 레이블 환경에서 메모리 무한 증가를 방지한다.
    private static final int MAX_HISTOGRAM_STATES = 1_000;
    private final Map<String, HistogramState> histogramStates = Collections.synchronizedMap(
            new LinkedHashMap<String, HistogramState>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HistogramState> eldest) {
                    return size() > MAX_HISTOGRAM_STATES;
                }
            });

    private static final class HistogramState {
        final long   count;
        final double sum;
        final long[] buckets;
        final long   timeNs;

        HistogramState(long count, double sum, long[] buckets, long timeNs) {
            this.count   = count;
            this.sum     = sum;
            this.buckets = buckets.clone();
            this.timeNs  = timeNs;
        }
    }

    private final OtlpHttpProtobufSender sender;
    private final String serviceName;
    private volatile byte[] cachedResourceBytes;
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
        final int points = totalPoints;

        byte[] protoBytes;
        try {
            protoBytes = buildExportBytes(metrics);
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpMetricExporter: 인코딩 오류 — " + e.getMessage());
            droppedPoints.addAndGet(points);
            return CompletableFuture.completedFuture(null);
        }

        return sender.sendAsync(METRIC_PATH, protoBytes)
                .thenAccept(result -> {
                    if (result == SendResult.SUCCESS) {
                        exportedPoints.addAndGet(points);
                    } else {
                        droppedPoints.addAndGet(points);
                        failedBatches.incrementAndGet();
                    }
                });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        isShutdown.set(true);
        return CompletableFuture.completedFuture(null);
    }

    // ---- 인코딩 (인스턴스 메서드: HISTOGRAM은 DELTA 상태 추적 필요) ----

    private byte[] getResourceBytes() {
        if (cachedResourceBytes == null) {
            synchronized (this) {
                if (cachedResourceBytes == null) cachedResourceBytes = encodeResource(serviceName);
            }
        }
        return cachedResourceBytes;
    }

    private byte[] buildExportBytes(Collection<MetricData> metrics) {
        byte[] resourceBytes = getResourceBytes();
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

    /** 하위 호환용 정적 메서드 (DELTA 상태 추적 없음, CUMULATIVE 히스토그램 인코딩). */
    static byte[] encodeExportRequest(Collection<MetricData> metrics, String serviceName) {
        return new OtlpHttpMetricExporter(serviceName, null).buildExportBytes(metrics);
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

    private byte[] encodeMetric(MetricData metric) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        ProtoEncoder.writeString(out, FN_METRIC_NAME, metric.getName());
        if (metric.getDescription() != null && !metric.getDescription().isEmpty())
            ProtoEncoder.writeString(out, FN_METRIC_DESCRIPTION, metric.getDescription());
        if (metric.getUnit() != null && !metric.getUnit().isEmpty())
            ProtoEncoder.writeString(out, FN_METRIC_UNIT, metric.getUnit());

        MetricData.MetricType type = metric.getType() != null ? metric.getType() : MetricData.MetricType.GAUGE;

        switch (type) {
            case GAUGE:
                ProtoEncoder.writeMessage(out, FN_METRIC_GAUGE, encodeGauge(metric.getPoints()));
                break;
            case SUM:
                ProtoEncoder.writeMessage(out, FN_METRIC_SUM, encodeSum(metric.getPoints()));
                break;
            case HISTOGRAM:
                ProtoEncoder.writeMessage(out, FN_METRIC_HISTOGRAM, encodeHistogramDelta(metric));
                break;
            case EXPONENTIAL_HISTOGRAM:
                ProtoEncoder.writeMessage(out, FN_METRIC_EXPONENTIAL_HISTOGRAM,
                        encodeExponentialHistogram(metric));
                break;
            default:
                ProtoEncoder.writeMessage(out, FN_METRIC_GAUGE, encodeGauge(metric.getPoints()));
        }
        return out.toByteArray();
    }

    private static byte[] encodeGauge(Collection<MetricData.Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 64);
        for (MetricData.Point point : points)
            ProtoEncoder.writeMessage(out, FN_GAUGE_DATA_POINTS, encodeNumberDataPoint(point, 0L));
        return out.toByteArray();
    }

    private static byte[] encodeSum(Collection<MetricData.Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 64 + 8);
        for (MetricData.Point point : points)
            ProtoEncoder.writeMessage(out, FN_SUM_DATA_POINTS, encodeNumberDataPoint(point, PROCESS_START_NS));
        ProtoEncoder.writeVarint32(out, FN_SUM_TEMPORALITY, TEMPORALITY_CUMULATIVE);
        ProtoEncoder.writeVarint32(out, FN_SUM_IS_MONOTONIC, 1);
        return out.toByteArray();
    }

    /**
     * DELTA temporality 히스토그램 인코딩.
     *
     * <p>MetricRegistry는 누적값을 유지하므로, 이전 export 시점의 누적값을 상태로
     * 보관하고 delta를 계산해 전송한다. 이를 통해 Prometheus(누적 스냅샷)와
     * OTLP DELTA 양쪽을 동시에 지원한다.
     */
    private byte[] encodeHistogramDelta(MetricData metric) {
        Collection<MetricData.HistogramPoint> hpts = metric.getHistogramPoints();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        if (hpts != null && !hpts.isEmpty()) {
            for (MetricData.HistogramPoint hp : hpts)
                ProtoEncoder.writeMessage(out, FN_HIST_DATA_POINTS, encodeDeltaPoint(metric.getName(), hp));
        } else {
            for (MetricData.Point point : metric.getPoints())
                ProtoEncoder.writeMessage(out, FN_HIST_DATA_POINTS, encodeHistogramDataPointSimple(point, PROCESS_START_NS));
        }
        ProtoEncoder.writeVarint32(out, FN_HIST_TEMPORALITY, TEMPORALITY_DELTA);
        return out.toByteArray();
    }

    /**
     * 누적 HistogramPoint에서 DELTA를 계산해 인코딩한다.
     * 첫 export는 프로세스 시작 이후 전체 누적값을 DELTA로 전송한다.
     */
    private byte[] encodeDeltaPoint(String metricName, MetricData.HistogramPoint hp) {
        long[] currBuckets = hp.getBucketCounts();
        long   currCount   = hp.getCount();
        double currSum     = hp.getSum();
        long   hpTimeNs    = hp.getTimestamp() != null
                ? (hp.getTimestamp().getEpochSecond() * 1_000_000_000L + hp.getTimestamp().getNano())
                : System.currentTimeMillis() * 1_000_000L;

        String key = buildHistogramKey(metricName, hp.getAttributes());
        HistogramState prev = histogramStates.get(key);

        long   startTimeNs;
        long   encCount;
        double encSum;
        long[] encBuckets;

        if (prev == null) {
            // 최초 export: 누적 전체를 DELTA로 (start = process start)
            startTimeNs = PROCESS_START_NS;
            encCount    = currCount;
            encSum      = currSum;
            encBuckets  = currBuckets;
        } else {
            startTimeNs = prev.timeNs;
            encCount    = Math.max(0L, currCount - prev.count);
            encSum      = Math.max(0.0, currSum  - prev.sum);
            encBuckets  = new long[currBuckets.length];
            for (int i = 0; i < currBuckets.length; i++) {
                encBuckets[i] = (i < prev.buckets.length)
                        ? Math.max(0L, currBuckets[i] - prev.buckets[i])
                        : currBuckets[i];
            }
        }

        // 상태 갱신 (현재 누적값 저장)
        histogramStates.put(key, new HistogramState(currCount, currSum, currBuckets, hpTimeNs));

        return encodeHistogramDataPointFull(hp, startTimeNs, hpTimeNs, encCount, encSum, encBuckets);
    }

    private static String buildHistogramKey(String metricName, Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return metricName + "|";
        // TreeMap으로 정렬해 동일 속성 조합이면 항상 동일 키 보장
        return metricName + "|" + new java.util.TreeMap<>(attrs);
    }

    /**
     * 버그 수정된 HistogramDataPoint 인코딩:
     * - count: uint64 → varint (wire type 0), NOT fixed64 (wire type 1)
     * - bucket_counts: repeated uint64 → packed varint, NOT packed fixed64
     * - exemplars: 이제 실제로 인코딩함
     */
    private static byte[] encodeHistogramDataPointFull(
            MetricData.HistogramPoint hp,
            long startTimeNs, long timeNs,
            long count, double sum, long[] bucketCounts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        encodeAttrs(out, FN_HDP_ATTRS, hp.getAttributes(), hp.getTypedAttributes());
        if (startTimeNs > 0) ProtoEncoder.writeFixed64Field(out, FN_HDP_START_TIME_NS, startTimeNs);
        if (timeNs > 0)      ProtoEncoder.writeFixed64Field(out, FN_HDP_TIME_NS, timeNs);

        // Fix: count은 uint64 → varint (이전 코드: writeFixed64Field → wire type mismatch)
        ProtoEncoder.writeUint64Field(out, FN_HDP_COUNT, count);
        ProtoEncoder.writeDoubleField(out, FN_HDP_SUM, sum);

        // Fix: bucket_counts은 repeated uint64 → packed varint (이전 코드: packed fixed64)
        ProtoEncoder.writePackedUint64(out, FN_HDP_BUCKET_COUNTS, bucketCounts);
        ProtoEncoder.writePackedDouble(out, FN_HDP_EXPLICIT_BOUNDS, hp.getBoundaries());

        // Fix: Exemplar 인코딩 (이전 코드: 수집은 했으나 OTLP 출력에서 누락)
        List<Exemplar> exemplars = hp.getExemplars();
        if (exemplars != null) {
            for (Exemplar ex : exemplars) {
                if (ex != null) ProtoEncoder.writeMessage(out, FN_HDP_EXEMPLARS, encodeExemplar(ex));
            }
        }

        if (hp.getMin() != 0) ProtoEncoder.writeDoubleField(out, FN_HDP_MIN, hp.getMin());
        if (hp.getMax() != 0) ProtoEncoder.writeDoubleField(out, FN_HDP_MAX, hp.getMax());
        return out.toByteArray();
    }

    private static byte[] encodeExemplar(Exemplar ex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        // filtered_attributes
        Map<String, String> filteredAttrs = ex.getFilteredAttributes();
        if (filteredAttrs != null && !filteredAttrs.isEmpty()) {
            for (Map.Entry<String, String> e : filteredAttrs.entrySet())
                ProtoEncoder.writeMessage(out, FN_EX_FILTERED_ATTRS, encodeStringKV(e.getKey(), e.getValue()));
        }
        ProtoEncoder.writeFixed64Field(out, FN_EX_TIME_NS, ex.getTimeUnixNano());
        ProtoEncoder.writeDoubleField(out, FN_EX_AS_DOUBLE, ex.getValue());
        byte[] spanId = ProtoEncoder.hexToBytes(ex.getSpanId());
        if (spanId != null) ProtoEncoder.writeBytes(out, FN_EX_SPAN_ID, spanId);
        byte[] traceId = ProtoEncoder.hexToBytes(ex.getTraceId());
        if (traceId != null) ProtoEncoder.writeBytes(out, FN_EX_TRACE_ID, traceId);
        // flags: SpanFlags (uint32, varint) — OTel Collector/Grafana Mimir가 trace 링크 검증에 사용
        int flags = ex.getTraceFlags() & 0xFF;
        if (flags != 0) ProtoEncoder.writeVarint32(out, FN_EX_FLAGS, flags);
        return out.toByteArray();
    }

    private static byte[] encodeNumberDataPoint(MetricData.Point point, long startTimeNs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        encodeAttrs(out, FN_NDP_ATTRS, point.getAttributes(), point.getTypedAttributes());
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
        encodeAttrs(out, FN_HDP_ATTRS, point.getAttributes(), point.getTypedAttributes());
        if (startTimeNs > 0) ProtoEncoder.writeFixed64Field(out, FN_HDP_START_TIME_NS, startTimeNs);
        if (point.getTimestamp() != null) {
            long nanos = point.getTimestamp().getEpochSecond() * 1_000_000_000L + point.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_HDP_TIME_NS, nanos);
        }
        ProtoEncoder.writeUint64Field(out, FN_HDP_COUNT, 1L);
        ProtoEncoder.writeDoubleField(out, FN_HDP_SUM, point.getValue());
        return out.toByteArray();
    }

    private static void encodePointAttributes(ByteArrayOutputStream out, int fieldNumber, Map<String, String> attrs) {
        if (attrs == null) return;
        for (Map.Entry<String, String> entry : attrs.entrySet())
            ProtoEncoder.writeMessage(out, fieldNumber, encodeStringKV(entry.getKey(), entry.getValue()));
    }

    /** typed 속성이 있으면 typed 인코딩, 없으면 string 인코딩으로 폴백. */
    private static void encodeAttrs(ByteArrayOutputStream out, int fieldNumber,
                                     Map<String, String> strAttrs, Map<String, Object> typedAttrs) {
        if (typedAttrs != null) {
            encodePointAttributesTyped(out, fieldNumber, typedAttrs);
        } else {
            encodePointAttributes(out, fieldNumber, strAttrs);
        }
    }

    private static void encodePointAttributesTyped(ByteArrayOutputStream out, int fieldNumber,
                                                     Map<String, Object> attrs) {
        if (attrs == null) return;
        for (Map.Entry<String, Object> entry : attrs.entrySet())
            ProtoEncoder.writeMessage(out, fieldNumber, encodeTypedKV(entry.getKey(), entry.getValue()));
    }

    /**
     * AnyValue oneof를 타입에 따라 인코딩한다.
     * Boolean → bool_value(field 2), Long/Integer → int_value(field 3),
     * Double/Float → double_value(field 4), 그 외 → string_value(field 1).
     * oneof 값이 0/false여도 타입 구분을 위해 항상 태그를 기록한다.
     */
    private static byte[] encodeTypedKV(String key, Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + 16);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ByteArrayOutputStream av = new ByteArrayOutputStream(16);
        if (value instanceof Boolean) {
            ProtoEncoder.writeTag(av, FN_AV_BOOL, ProtoEncoder.WIRE_VARINT);
            ProtoEncoder.writeRawVarint32(av, ((Boolean) value) ? 1 : 0);
        } else if (value instanceof Long) {
            ProtoEncoder.writeTag(av, FN_AV_INT, ProtoEncoder.WIRE_VARINT);
            ProtoEncoder.writeRawVarint64(av, (Long) value);
        } else if (value instanceof Integer) {
            ProtoEncoder.writeTag(av, FN_AV_INT, ProtoEncoder.WIRE_VARINT);
            ProtoEncoder.writeRawVarint64(av, (long) (int) (Integer) value);
        } else if (value instanceof Double) {
            ProtoEncoder.writeTag(av, FN_AV_DOUBLE, ProtoEncoder.WIRE_64BIT);
            ProtoEncoder.writeDouble(av, (Double) value);
        } else if (value instanceof Float) {
            ProtoEncoder.writeTag(av, FN_AV_DOUBLE, ProtoEncoder.WIRE_64BIT);
            ProtoEncoder.writeDouble(av, (double) (float) (Float) value);
        } else {
            ProtoEncoder.writeStringAlways(av, FN_AV_STRING, value != null ? value.toString() : "");
        }
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
        return out.toByteArray();
    }

    private static byte[] encodeStringKV(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + (value != null ? value.length() : 0) + 8);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ByteArrayOutputStream av = new ByteArrayOutputStream((value != null ? value.length() : 0) + 4);
        ProtoEncoder.writeString(av, FN_AV_STRING, value != null ? value : "");
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
        return out.toByteArray();
    }

    // ---- ExponentialHistogram 인코딩 (Item 6) ----

    private static byte[] encodeExponentialHistogram(MetricData metric) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        Collection<MetricData.ExponentialHistogramPoint> pts = metric.getExponentialHistogramPoints();
        if (pts != null) {
            for (MetricData.ExponentialHistogramPoint p : pts)
                ProtoEncoder.writeMessage(out, FN_EH_DATA_POINTS, encodeExpHistogramDataPoint(p));
        }
        ProtoEncoder.writeVarint32(out, FN_EH_TEMPORALITY, TEMPORALITY_CUMULATIVE);
        return out.toByteArray();
    }

    private static byte[] encodeExpHistogramDataPoint(MetricData.ExponentialHistogramPoint p) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        encodePointAttributes(out, FN_EHP_ATTRS, p.getAttributes());

        long timeNs = p.getTimestamp() != null
                ? p.getTimestamp().getEpochSecond() * 1_000_000_000L + p.getTimestamp().getNano()
                : 0L;
        if (timeNs > 0) ProtoEncoder.writeFixed64Field(out, FN_EHP_TIME, timeNs);

        ProtoEncoder.writeUint64Field(out, FN_EHP_COUNT, p.getCount());
        // sum은 optional — 수집 안 된 경우(NaN 등) 기록하지 않는다
        double sum = p.getSum();
        if (Double.isFinite(sum) && sum != 0.0)
            ProtoEncoder.writeDoubleField(out, FN_EHP_SUM, sum);

        ProtoEncoder.writeSint32Field(out, FN_EHP_SCALE, p.getScale());
        ProtoEncoder.writeUint64Field(out, FN_EHP_ZERO_COUNT, p.getZeroCount());

        if (p.getPositiveBucketCounts() != null && p.getPositiveBucketCounts().length > 0)
            ProtoEncoder.writeMessage(out, FN_EHP_POSITIVE,
                    encodeBuckets(p.getPositiveOffset(), p.getPositiveBucketCounts()));
        if (p.getNegativeBucketCounts() != null && p.getNegativeBucketCounts().length > 0)
            ProtoEncoder.writeMessage(out, FN_EHP_NEGATIVE,
                    encodeBuckets(p.getNegativeOffset(), p.getNegativeBucketCounts()));

        for (Exemplar ex : p.getExemplars())
            if (ex != null) ProtoEncoder.writeMessage(out, FN_EHP_EXEMPLARS, encodeExemplar(ex));

        if (Double.isFinite(p.getMin()) && p.getMin() != 0)
            ProtoEncoder.writeDoubleField(out, FN_EHP_MIN, p.getMin());
        if (Double.isFinite(p.getMax()) && p.getMax() != 0)
            ProtoEncoder.writeDoubleField(out, FN_EHP_MAX, p.getMax());

        return out.toByteArray();
    }

    private static byte[] encodeBuckets(int offset, long[] counts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(counts.length * 4 + 4);
        ProtoEncoder.writeSint32Field(out, FN_BUCKETS_OFFSET, offset);
        ProtoEncoder.writePackedUint64(out, FN_BUCKETS_COUNTS, counts);
        return out.toByteArray();
    }

    private static String resolveServiceName() {
        String val = System.getenv("JAVI_SERVICE_NAME");
        return (val != null && !val.isEmpty()) ? val : System.getProperty("javi.service.name", "javi-service");
    }
}
