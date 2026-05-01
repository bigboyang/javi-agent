package com.agent.metric;

import com.agent.common.JaviSdk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // ── 이름별 사전 그룹화 인덱스 (scrapeAndEmit 임시 HashMap 제거용) ──────────
    // 등록(write)은 드물고 스크레이핑(read)은 반복적 → CopyOnWriteArrayList 최적
    private final ConcurrentHashMap<String, List<Counter>>                 countersByName     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Gauge>>                   gaugesByName       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ExplicitBucketHistogram>> bucketsByName      = new ConcurrentHashMap<>();

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
        return counters.computeIfAbsent(key, k -> {
            Counter c = new Counter(name, description, unit, k.getAttributes());
            countersByName.computeIfAbsent(name, n -> new CopyOnWriteArrayList<>()).add(c);
            return c;
        });
    }

    /** 게이지 획득 또는 생성 (속성 기반) */
    public Gauge gauge(String name, Map<String, String> attributes) {
        return gauge(name, "", "1", attributes);
    }

    public Gauge gauge(String name, String description, String unit, Map<String, String> attributes) {
        MetricKey key = new MetricKey(name, attributes);
        return gauges.computeIfAbsent(key, k -> {
            Gauge g = new Gauge(name, description, unit, k.getAttributes());
            gaugesByName.computeIfAbsent(name, n -> new CopyOnWriteArrayList<>()).add(g);
            return g;
        });
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
        return bucketHistograms.computeIfAbsent(key, k -> {
            ExplicitBucketHistogram h = new ExplicitBucketHistogram(name, description, unit, k.getAttributes());
            bucketsByName.computeIfAbsent(name, n -> new CopyOnWriteArrayList<>()).add(h);
            return h;
        });
    }

    public ExplicitBucketHistogram histogram(String name, String description, String unit,
                                             Map<String, String> attributes, double[] boundaries) {
        MetricKey key = new MetricKey(name, attributes);
        return bucketHistograms.computeIfAbsent(key, k -> {
            ExplicitBucketHistogram h = new ExplicitBucketHistogram(name, description, unit, k.getAttributes(), boundaries);
            bucketsByName.computeIfAbsent(name, n -> new CopyOnWriteArrayList<>()).add(h);
            return h;
        });
    }

    /**
     * 현재 등록된 모든 메트릭의 스냅샷을 찍어 SdkMeterProvider로 전송한다.
     * 주기적으로 호출된다 (예: JvmMetricsCollector 수집 시).
     */
    public void scrapeAndEmit() {
        JaviSdk sdk = JaviSdk.get();
        if (sdk == null) return;

        // 배치 내 모든 포인트에 동일한 타임스탬프 적용 (1회 캡처)
        Instant now = Instant.now();

        // 1. Counter → SUM (Monotonic) — 사전 그룹화 인덱스 사용, 임시 HashMap 없음
        for (Map.Entry<String, List<Counter>> entry : countersByName.entrySet()) {
            List<Counter> list = entry.getValue();
            List<MetricData.Point> pts = new ArrayList<>(list.size());
            Counter first = null;
            for (Counter c : list) {
                if (first == null) first = c;
                pts.add(new MetricData.Point(c.get(), c.getAttributes(), now));
            }
            if (first != null) {
                sdk.getMeterProvider().record(new MetricData(
                        entry.getKey(), first.getDescription(), first.getUnit(), MetricData.MetricType.SUM, pts));
            }
        }

        // 2. Gauge → GAUGE — 사전 그룹화 인덱스 사용, 임시 HashMap 없음
        for (Map.Entry<String, List<Gauge>> entry : gaugesByName.entrySet()) {
            List<Gauge> list = entry.getValue();
            List<MetricData.Point> pts = new ArrayList<>(list.size());
            Gauge first = null;
            for (Gauge g : list) {
                if (first == null) first = g;
                pts.add(new MetricData.Point(g.get(), g.getAttributes(), now));
            }
            if (first != null) {
                sdk.getMeterProvider().record(new MetricData(
                        entry.getKey(), first.getDescription(), first.getUnit(), MetricData.MetricType.GAUGE, pts));
            }
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

        // 4. ExplicitBucketHistogram → OTLP Histogram — 사전 그룹화 + double[] 캐시 사용
        for (Map.Entry<String, List<ExplicitBucketHistogram>> entry : bucketsByName.entrySet()) {
            List<ExplicitBucketHistogram> list = entry.getValue();
            List<MetricData.HistogramPoint> hpts = new ArrayList<>(list.size());
            ExplicitBucketHistogram first = null;
            for (ExplicitBucketHistogram hist : list) {
                long count = hist.getCount();
                if (count == 0) continue;
                if (first == null) first = hist;
                // getDoubleBoundaries(): 생성자에서 1회 변환된 캐시 — 루프마다 double[] 재할당 없음
                List<Exemplar> exemplars = hist.collectAndResetExemplars();
                double[] minMax = hist.collectAndResetMinMax(); // DELTA 구간 min/max 원자적 리셋
                hpts.add(new MetricData.HistogramPoint(
                        hist.getAttributes(), now,
                        count, hist.getSum(), minMax[0], minMax[1],
                        hist.getBucketCounts(), hist.getDoubleBoundaries(), exemplars));
            }
            if (first != null) {
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
