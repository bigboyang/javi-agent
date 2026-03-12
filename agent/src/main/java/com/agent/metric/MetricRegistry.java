package com.agent.metric;

import com.agent.common.JaviSdk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTel/DataDog 방식의 다차원 메트릭 엔진.
 * (Name + Attributes) 조합으로 메트릭을 수집하고 전송한다.
 *
 * <p>지원 타입:
 * <ul>
 *   <li>{@link Counter}  — monotonic sum (요청 수, 에러 수 등)</li>
 *   <li>{@link Gauge}    — 현재 값 (메모리, 커넥션 수 등)</li>
 *   <li>{@link Histogram} — count/sum/min/max (기존 호환)</li>
 *   <li>{@link ExplicitBucketHistogram} — P50/P95/P99 지원 버킷 히스토그램</li>
 * </ul>
 */
public final class MetricRegistry {

    private static final MetricRegistry INSTANCE = new MetricRegistry();

    private final ConcurrentHashMap<MetricKey, Counter>                   counters           = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MetricKey, Gauge>                     gauges             = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram>                    histograms         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MetricKey, ExplicitBucketHistogram>   bucketHistograms   = new ConcurrentHashMap<>();

    private MetricRegistry() {}

    public static MetricRegistry get() {
        return INSTANCE;
    }

    /** 카운터 획득 또는 생성 (속성 기반) */
    public Counter counter(String name, Map<String, String> attributes) {
        return counter(name, "", "1", attributes);
    }

    public Counter counter(String name, String description, String unit, Map<String, String> attributes) {
        MetricKey key = new MetricKey(name, attributes);
        return counters.computeIfAbsent(key, k -> new Counter(name, description, unit, k.getAttributes()));
    }

    /** 게이지 획득 또는 생성 (속성 기반) */
    public Gauge gauge(String name, Map<String, String> attributes) {
        return gauge(name, "", "1", attributes);
    }

    public Gauge gauge(String name, String description, String unit, Map<String, String> attributes) {
        MetricKey key = new MetricKey(name, attributes);
        return gauges.computeIfAbsent(key, k -> new Gauge(name, description, unit, k.getAttributes()));
    }

    /** 히스토그램 획득 또는 생성 (이름 기반, 속성 없음 — 하위 호환용). */
    public Histogram histogram(String name) {
        return histograms.computeIfAbsent(name, Histogram::new);
    }

    /**
     * ExplicitBucketHistogram 획득 또는 생성 (속성 기반).
     */
    public ExplicitBucketHistogram histogram(String name, Map<String, String> attributes) {
        return histogram(name, "", "ms", attributes);
    }

    public ExplicitBucketHistogram histogram(String name, String description, String unit, Map<String, String> attributes) {
        MetricKey key = new MetricKey(name, attributes);
        return bucketHistograms.computeIfAbsent(key,
                k -> new ExplicitBucketHistogram(name, description, unit, k.getAttributes()));
    }

    /**
     * 현재 등록된 모든 메트릭의 스냅샷을 찍어 SdkMeterProvider로 전송한다.
     * 주기적으로 호출된다 (예: JvmMetricsCollector 수집 시).
     */
    public void scrapeAndEmit() {
        JaviSdk sdk = JaviSdk.get();
        if (sdk == null) return;

        Instant now = Instant.now();

        // 1. Counter → SUM (Monotonic)
        Map<String, List<Counter>> countersByName = new HashMap<>();
        for (Counter c : counters.values()) {
            countersByName.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<Counter>> entry : countersByName.entrySet()) {
            List<MetricData.Point> pts = new ArrayList<>(entry.getValue().size());
            Counter first = entry.getValue().get(0);
            for (Counter c : entry.getValue()) {
                pts.add(new MetricData.Point(c.get(), c.getAttributes(), now));
            }
            sdk.getMeterProvider().record(new MetricData(
                    entry.getKey(), first.getDescription(), first.getUnit(), MetricData.MetricType.SUM, pts));
        }

        // 2. Gauge → GAUGE
        Map<String, List<Gauge>> gaugesByName = new HashMap<>();
        for (Gauge g : gauges.values()) {
            gaugesByName.computeIfAbsent(g.getName(), k -> new ArrayList<>()).add(g);
        }
        for (Map.Entry<String, List<Gauge>> entry : gaugesByName.entrySet()) {
            List<MetricData.Point> pts = new ArrayList<>(entry.getValue().size());
            Gauge first = entry.getValue().get(0);
            for (Gauge g : entry.getValue()) {
                pts.add(new MetricData.Point(g.get(), g.getAttributes(), now));
            }
            sdk.getMeterProvider().record(new MetricData(
                    entry.getKey(), first.getDescription(), first.getUnit(), MetricData.MetricType.GAUGE, pts));
        }

        // 3. 기존 Histogram → count/sum/min/max 4개 메트릭 분해 (하위 호환)
        for (Histogram hist : histograms.values()) {
            long count = hist.getCount();
            if (count == 0) continue;
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".count", "", "1",
                    MetricData.MetricType.SUM,
                    Collections.singletonList(new MetricData.Point(count, Collections.emptyMap(), now))));
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".sum", "", "1",
                    MetricData.MetricType.SUM,
                    Collections.singletonList(new MetricData.Point(hist.getSum(), Collections.emptyMap(), now))));
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".min", "", "1",
                    MetricData.MetricType.GAUGE,
                    Collections.singletonList(new MetricData.Point(hist.getMin(), Collections.emptyMap(), now))));
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".max", "", "1",
                    MetricData.MetricType.GAUGE,
                    Collections.singletonList(new MetricData.Point(hist.getMax(), Collections.emptyMap(), now))));
        }

        // 4. ExplicitBucketHistogram → OTLP Histogram (P50/P95/P99 + Exemplar 포함)
        Map<String, List<ExplicitBucketHistogram>> bucketsByName = new HashMap<>();
        for (Map.Entry<MetricKey, ExplicitBucketHistogram> entry : bucketHistograms.entrySet()) {
            String metricName = entry.getKey().getName();
            bucketsByName.computeIfAbsent(metricName, k -> new ArrayList<>()).add(entry.getValue());
        }
        for (Map.Entry<String, List<ExplicitBucketHistogram>> entry : bucketsByName.entrySet()) {
            List<MetricData.HistogramPoint> hpts = new ArrayList<>(entry.getValue().size());
            ExplicitBucketHistogram first = entry.getValue().get(0);
            for (ExplicitBucketHistogram hist : entry.getValue()) {
                long count = hist.getCount();
                if (count == 0) continue;
                long[] lBounds = hist.getBoundaries();
                double[] dBounds = new double[lBounds.length];
                for (int i = 0; i < lBounds.length; i++) dBounds[i] = lBounds[i];
                // Exemplar 수집 후 슬롯 리셋 (다음 주기에 새 exemplar 수집)
                List<Exemplar> exemplars = hist.collectAndResetExemplars();
                hpts.add(new MetricData.HistogramPoint(
                        hist.getAttributes(), now,
                        count, hist.getSum(), hist.getMin(), hist.getMax(),
                        hist.getBucketCounts(), dBounds, exemplars));
            }
            if (!hpts.isEmpty()) {
                sdk.getMeterProvider().record(
                        MetricData.ofHistogram(entry.getKey(), first.getDescription(), first.getUnit(), hpts));
            }
        }
    }

    public Map<MetricKey, Counter>                   counters()        { return Collections.unmodifiableMap(counters); }
    public Map<MetricKey, Gauge>                     gauges()          { return Collections.unmodifiableMap(gauges); }
    public Map<String, Histogram>                    histograms()      { return Collections.unmodifiableMap(histograms); }
    public Map<MetricKey, ExplicitBucketHistogram>   bucketHistograms(){ return Collections.unmodifiableMap(bucketHistograms); }
}
