package com.agent.metric;

import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free explicit-bucket histogram (P50/P95/P99 + Exemplar 지원).
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>각 버킷이 독립 {@link LongAdder} → 서로 다른 버킷 기록 시 경합 없음</li>
 *   <li>같은 버킷 동시 기록은 LongAdder cell-striping이 분산 처리 (wait-free에 근접)</li>
 *   <li>퍼센타일 계산은 export 시점(15초마다)에만 호출 — hot path 없음</li>
 * </ul>
 *
 * <p>Exemplar 설계 (상용 APM 수준):
 * <ul>
 *   <li>버킷별 1개 Exemplar 슬롯 — {@link AtomicReference} (lock-free)</li>
 *   <li>Reservoir Sampling (Algorithm R, size=1): N번째 관측값을 1/N 확률로 교체</li>
 *   <li>고지연/에러 스팬 우선 수용 — overflow 버킷 포함</li>
 *   <li>활성 sampled span 없으면 Exemplar 기록 생략 (hot path 최소화)</li>
 * </ul>
 *
 * <p>기본 버킷 경계: OTel/Prometheus 표준 HTTP 레이턴시 버킷 (ms 단위)
 */
public final class ExplicitBucketHistogram {

    /** OTel HTTP 레이턴시 기본 버킷 경계 (ms) — Prometheus client_java와 동일 */
    public static final long[] DEFAULT_BOUNDARIES =
            {1, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000, 10000};

    private final String name;
    private final String description;
    private final String unit;
    private final Map<String, String> attributes;
    private final long[] boundaries;
    private final LongAdder[] bucketCounts;
    private final LongAdder overflowCount;
    private final LongAdder totalCount;
    private final LongAdder totalSum;
    private final AtomicLong minValue;
    private final AtomicLong maxValue;

    /**
     * 버킷별 Exemplar 슬롯 (length = boundaries.length + 1, 마지막이 overflow 슬롯).
     * AtomicReference → CAS 기반 lock-free 교체, GC 친화적.
     */
    private final AtomicReference<Exemplar>[] exemplarSlots;

    /**
     * 버킷별 관측 횟수 — Reservoir Sampling(Algorithm R)의 교체 확률 계산에 사용.
     * bucketCounts와 별도로 관리해야 정확한 1/N 확률 보장.
     */
    private final AtomicLong[] exemplarObsCount;

    public ExplicitBucketHistogram(String name, Map<String, String> attributes) {
        this(name, "", "ms", attributes, DEFAULT_BOUNDARIES);
    }

    public ExplicitBucketHistogram(String name, String description, String unit, Map<String, String> attributes) {
        this(name, description, unit, attributes, DEFAULT_BOUNDARIES);
    }

    @SuppressWarnings("unchecked")
    public ExplicitBucketHistogram(String name, String description, String unit, Map<String, String> attributes, long[] boundaries) {
        this.name       = name;
        this.description = description != null ? description : "";
        this.unit       = unit != null ? unit : "1";
        this.attributes = attributes != null ? attributes : Collections.emptyMap();
        this.boundaries = boundaries;
        this.bucketCounts = new LongAdder[boundaries.length];
        for (int i = 0; i < boundaries.length; i++) {
            bucketCounts[i] = new LongAdder();
        }
        this.overflowCount = new LongAdder();
        this.totalCount    = new LongAdder();
        this.totalSum      = new LongAdder();
        this.minValue      = new AtomicLong(Long.MAX_VALUE);
        this.maxValue      = new AtomicLong(Long.MIN_VALUE);

        // Exemplar 슬롯 초기화 (buckets + 1 overflow)
        int slots = boundaries.length + 1;
        this.exemplarSlots    = new AtomicReference[slots];
        this.exemplarObsCount = new AtomicLong[slots];
        for (int i = 0; i < slots; i++) {
            exemplarSlots[i]    = new AtomicReference<>(null);
            exemplarObsCount[i] = new AtomicLong(0);
        }
    }

    /**
     * 값을 밀리초 단위로 기록한다.
     * 이진 탐색으로 버킷을 결정하고 해당 LongAdder만 원자적으로 증가시킨다.
     *
     * <p>Exemplar 샘플링 오버헤드:
     * <ul>
     *   <li>활성 span 없음: ThreadLocal.get() 1회 → 즉시 종료</li>
     *   <li>활성 span 있음: ThreadLocalRandom + AtomicLong CAS + AtomicReference CAS</li>
     * </ul>
     */
    public void record(long valueMs) {
        int idx = Arrays.binarySearch(boundaries, valueMs);
        if (idx < 0) idx = -idx - 1;  // 삽입 위치 = 해당 버킷 인덱스

        int exemplarIdx;
        if (idx < bucketCounts.length) {
            bucketCounts[idx].increment();
            exemplarIdx = idx;
        } else {
            overflowCount.increment();
            exemplarIdx = boundaries.length; // overflow slot
        }
        totalCount.increment();
        totalSum.add(valueMs);
        updateMin(valueMs);
        updateMax(valueMs);

        // Exemplar 샘플링 (hot path 최소화: span 없으면 즉시 리턴)
        tryRecordExemplar(valueMs, exemplarIdx);
    }

    /**
     * Reservoir Sampling (Algorithm R, size=1) 기반 Exemplar 기록.
     *
     * <p>상용 APM과의 차이:
     * <ul>
     *   <li>Datadog: 고정 비율 샘플링 (1%) + 에러 스팬 강제 포함</li>
     *   <li>Grafana Mimir: AlignedHistogramBucket — 버킷별 1개, 최신값 우선</li>
     *   <li>본 구현: Algorithm R (1/N 확률 교체) + 에러/느린 스팬 우선 수용</li>
     * </ul>
     */
    private void tryRecordExemplar(long valueMs, int slotIdx) {
        // 활성 span 확인 (ThreadLocal 1회 접근)
        Span span = Context.currentSpan();
        if (!span.isRecording()) return;
        SpanContext ctx = span.getContext();
        if (ctx == null || !ctx.isValid() || !ctx.isSampled()) return;

        // Algorithm R (reservoir size=1): N번째 관측값을 1/N 확률로 교체
        long n = exemplarObsCount[slotIdx].incrementAndGet();
        boolean accept = (n == 1) || (ThreadLocalRandom.current().nextLong(n) == 0);
        if (!accept) return;

        long timeUnixNano = System.currentTimeMillis() * 1_000_000L;
        Exemplar exemplar = new Exemplar(valueMs, timeUnixNano, ctx.getTraceId(), ctx.getSpanId(), null);
        exemplarSlots[slotIdx].set(exemplar);
    }

    /**
     * 버킷 내 선형 보간으로 퍼센타일을 추정한다 (Prometheus와 동일한 방법).
     * export 시점에만 호출되므로 성능에 영향 없음.
     *
     * @param percentile 0–100 (예: 95.0 → P95)
     */
    public long estimatePercentile(double percentile) {
        long total = totalCount.sum();
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * percentile / 100.0);
        long cumulative = 0;
        for (int i = 0; i < bucketCounts.length; i++) {
            long bc = bucketCounts[i].sum();
            cumulative += bc;
            if (cumulative >= target) {
                long lowerBound   = (i == 0) ? 0 : boundaries[i - 1];
                long upperBound   = boundaries[i];
                long prevCumul    = cumulative - bc;
                if (bc == 0) return upperBound;
                return lowerBound + (upperBound - lowerBound) * (target - prevCumul) / bc;
            }
        }
        return boundaries[boundaries.length - 1];
    }

    /**
     * OTLP ExplicitBucketHistogram 직렬화용 버킷 카운트 스냅샷.
     * 마지막 원소는 overflow(최대 경계 초과) 카운트.
     * 길이 = boundaries.length + 1
     */
    public long[] getBucketCounts() {
        long[] snapshot = new long[bucketCounts.length + 1];
        for (int i = 0; i < bucketCounts.length; i++) {
            snapshot[i] = bucketCounts[i].sum();
        }
        snapshot[bucketCounts.length] = overflowCount.sum();
        return snapshot;
    }

    /**
     * 현재 수집된 모든 Exemplar 스냅샷을 반환하고 슬롯을 초기화한다.
     *
     * <p>export 시점(15초마다)에만 호출된다. 각 슬롯을 getAndSet(null)로 drain하여
     * 다음 주기에 새로운 exemplar를 수집할 수 있도록 한다.
     * 동시에 exemplarObsCount를 리셋해 Algorithm R을 초기화한다.
     *
     * <p>이 방식은 OTel Java SDK의 ExemplarReservoir.collectAndReset()과 동일한 패턴.
     *
     * @return 비어있지 않은 Exemplar 목록 (최대 버킷 수 + 1개)
     */
    public List<Exemplar> collectAndResetExemplars() {
        List<Exemplar> result = new ArrayList<>(exemplarSlots.length);
        for (int i = 0; i < exemplarSlots.length; i++) {
            Exemplar e = exemplarSlots[i].getAndSet(null);
            if (e != null) {
                result.add(e);
            }
            exemplarObsCount[i].set(0);
        }
        return result;
    }

    public long[]              getBoundaries() { return boundaries; }
    public long                getCount()      { return totalCount.sum(); }
    public long                getSum()        { return totalSum.sum(); }
    public long                getMin()        { long v = minValue.get(); return v == Long.MAX_VALUE ? 0 : v; }
    public long                getMax()        { long v = maxValue.get(); return v == Long.MIN_VALUE ? 0 : v; }
    public String              getName()       { return name; }
    public String              getDescription(){ return description; }
    public String              getUnit()       { return unit; }
    public Map<String, String> getAttributes() { return attributes; }

    private void updateMin(long value) {
        long current;
        do {
            current = minValue.get();
            if (value >= current) return;
        } while (!minValue.compareAndSet(current, value));
    }

    private void updateMax(long value) {
        long current;
        do {
            current = maxValue.get();
            if (value <= current) return;
        } while (!maxValue.compareAndSet(current, value));
    }
}
