package com.agent.metric;

import com.agent.common.JaviSdk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OTel/DataDog 방식의 다차원 메트릭 엔진.
 * (Name + Attributes) 조합으로 메트릭을 수집하고 전송한다.
 */
public final class MetricRegistry {

    private static final MetricRegistry INSTANCE = new MetricRegistry();

    private final ConcurrentHashMap<MetricKey, Counter>   counters   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MetricKey, Gauge>     gauges     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram>    histograms = new ConcurrentHashMap<>();

    private MetricRegistry() {}

    public static MetricRegistry get() {
        return INSTANCE;
    }

    /** 카운터 획득 또는 생성 (속성 기반) */
    public Counter counter(String name, Map<String, String> attributes) {
        MetricKey key = new MetricKey(name, attributes);
        return counters.computeIfAbsent(key, k -> new Counter(name, k.getAttributes()));
    }

    /** 게이지 획득 또는 생성 (속성 기반) */
    public Gauge gauge(String name, Map<String, String> attributes) {
        MetricKey key = new MetricKey(name, attributes);
        return gauges.computeIfAbsent(key, k -> new Gauge(name, k.getAttributes()));
    }

    /** 히스토그램 획득 또는 생성 (이름 기반, 속성 없음). */
    public Histogram histogram(String name) {
        return histograms.computeIfAbsent(name, Histogram::new);
    }

    /** 
     * 현재 등록된 모든 메트릭의 스냅샷을 찍어 SdkMeterProvider로 전송한다.
     * 주기적으로 호출된다 (예: JvmMetricsCollector 수집 시).
     */
    public void scrapeAndEmit() {
        JaviSdk sdk = JaviSdk.get();
        if (sdk == null) return;

        // 1. Counter들을 MetricData로 변환 (Sum 타입)
        Map<String, List<Counter>> countersGroupedByName = counters.values().stream()
                .collect(Collectors.groupingBy(Counter::getName));

        for (Map.Entry<String, List<Counter>> entry : countersGroupedByName.entrySet()) {
            List<MetricData.Point> points = entry.getValue().stream()
                    .map(c -> new MetricData.Point(c.get(), c.getAttributes(), Instant.now()))
                    .collect(Collectors.toList());

            sdk.getMeterProvider().record(new MetricData(
                    entry.getKey(), "", "count", MetricData.MetricType.SUM, points
            ));
        }

        // 2. Gauge들을 MetricData로 변환 (Gauge 타입)
        Map<String, List<Gauge>> gaugesGroupedByName = gauges.values().stream()
                .collect(Collectors.groupingBy(Gauge::getName));

        for (Map.Entry<String, List<Gauge>> entry : gaugesGroupedByName.entrySet()) {
            List<MetricData.Point> points = entry.getValue().stream()
                    .map(g -> new MetricData.Point(g.get(), g.getAttributes(), Instant.now()))
                    .collect(Collectors.toList());

            sdk.getMeterProvider().record(new MetricData(
                    entry.getKey(), "", "value", MetricData.MetricType.GAUGE, points
            ));
        }

        // 3. Histogram들을 count/sum/min/max 4개 메트릭으로 분해
        Instant now = Instant.now();
        for (Histogram hist : histograms.values()) {
            long count = hist.getCount();
            if (count == 0) continue;

            List<MetricData.Point> countPt = Collections.singletonList(
                    new MetricData.Point(count, Collections.emptyMap(), now));
            List<MetricData.Point> sumPt = Collections.singletonList(
                    new MetricData.Point(hist.getSum(), Collections.emptyMap(), now));
            List<MetricData.Point> minPt = Collections.singletonList(
                    new MetricData.Point(hist.getMin(), Collections.emptyMap(), now));
            List<MetricData.Point> maxPt = Collections.singletonList(
                    new MetricData.Point(hist.getMax(), Collections.emptyMap(), now));

            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".count",  "", "count", MetricData.MetricType.SUM,   countPt));
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".sum",    "", "1",     MetricData.MetricType.SUM,   sumPt));
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".min",    "", "1",     MetricData.MetricType.GAUGE, minPt));
            sdk.getMeterProvider().record(new MetricData(hist.getName() + ".max",    "", "1",     MetricData.MetricType.GAUGE, maxPt));
        }
    }

    public Map<MetricKey, Counter> counters() {
        return Collections.unmodifiableMap(counters);
    }

    public Map<MetricKey, Gauge> gauges() {
        return Collections.unmodifiableMap(gauges);
    }

    public Map<String, Histogram> histograms() {
        return Collections.unmodifiableMap(histograms);
    }
}
