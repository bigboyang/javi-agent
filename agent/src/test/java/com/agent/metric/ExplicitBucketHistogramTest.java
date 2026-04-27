package com.agent.metric;

import com.agent.span.Context;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.TraceFlags;
import com.agent.span.TraceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitBucketHistogramTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID  = "b7ad6b7169203331";

    private ExplicitBucketHistogram histogram;

    @BeforeEach
    void setUp() {
        histogram = new ExplicitBucketHistogram("test.latency", Collections.emptyMap());
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 오염 방지: 테스트 후 Context 정리
        Context.makeCurrent(Span.invalid()).close();
    }

    // ── Exemplar 캡처 ──────────────────────────────────────────────────────────

    @Test
    void exemplarCapturedWhenSampledSpanActive() {
        SpanContext ctx = SpanContext.create(TRACE_ID, SPAN_ID, "0000000000000000", true,
                TraceFlags.sampled(), TraceState.getDefault());
        Span span = Span.wrap(ctx);

        try (Scope scope = Context.makeCurrent(span)) {
            histogram.record(100L);
        }

        List<Exemplar> exemplars = histogram.collectAndResetExemplars();
        assertEquals(1, exemplars.size());

        Exemplar ex = exemplars.get(0);
        assertEquals(TRACE_ID, ex.getTraceId());
        assertEquals(SPAN_ID, ex.getSpanId());
        assertEquals((byte) 0x01, ex.getTraceFlags());  // SAMPLED=0x01
        assertEquals(100.0, ex.getValue(), 0.001);
        assertTrue(ex.getTimeUnixNano() > 0);
    }

    @Test
    void noExemplarWhenNoActiveSpan() {
        histogram.record(100L);

        List<Exemplar> exemplars = histogram.collectAndResetExemplars();
        assertTrue(exemplars.isEmpty(), "스팬 없을 때 Exemplar가 기록되면 안 됨");
    }

    @Test
    void noExemplarWhenSpanNotSampled() {
        SpanContext ctx = SpanContext.create(TRACE_ID, SPAN_ID, "0000000000000000", false,
                TraceFlags.getDefault(), TraceState.getDefault());
        Span span = Span.wrap(ctx);

        try (Scope scope = Context.makeCurrent(span)) {
            histogram.record(100L);
        }

        List<Exemplar> exemplars = histogram.collectAndResetExemplars();
        assertTrue(exemplars.isEmpty(), "unsampled 스팬은 Exemplar에서 제외되어야 함");
    }

    @Test
    void noExemplarWhenInvalidSpanContext() {
        // SpanContext.invalid()는 isValid() == false
        Span span = Span.wrap(SpanContext.invalid());

        try (Scope scope = Context.makeCurrent(span)) {
            histogram.record(50L);
        }

        List<Exemplar> exemplars = histogram.collectAndResetExemplars();
        assertTrue(exemplars.isEmpty());
    }

    // ── Exemplar 슬롯이 버킷별로 독립 관리되는지 확인 ─────────────────────────

    @Test
    void exemplarPerBucketSlot() {
        SpanContext ctx = SpanContext.create(TRACE_ID, SPAN_ID, "0000000000000000", true,
                TraceFlags.sampled(), TraceState.getDefault());
        Span span = Span.wrap(ctx);

        // 서로 다른 버킷에 기록 (boundaries: 1,5,10,25,50,75,100,...)
        try (Scope scope = Context.makeCurrent(span)) {
            histogram.record(10L);   // 버킷 idx=2
            histogram.record(250L);  // 버킷 idx=8
        }

        List<Exemplar> exemplars = histogram.collectAndResetExemplars();
        assertEquals(2, exemplars.size(), "서로 다른 버킷에 각 1개씩 Exemplar가 있어야 함");
    }

    // ── collectAndReset 후 슬롯 초기화 ──────────────────────────────────────

    @Test
    void collectAndResetClearsSlots() {
        SpanContext ctx = SpanContext.create(TRACE_ID, SPAN_ID, "0000000000000000", true,
                TraceFlags.sampled(), TraceState.getDefault());
        Span span = Span.wrap(ctx);

        try (Scope scope = Context.makeCurrent(span)) {
            histogram.record(100L);
        }

        histogram.collectAndResetExemplars(); // 첫 번째 drain

        // 두 번째 collect: 슬롯이 초기화되어 비어 있어야 함
        List<Exemplar> second = histogram.collectAndResetExemplars();
        assertTrue(second.isEmpty(), "collectAndReset 후 슬롯이 초기화되어야 함");
    }

    // ── scope.close() 이후에는 Exemplar가 캡처되지 않아야 한다 ────────────────

    @Test
    void noExemplarAfterScopeClose() {
        SpanContext ctx = SpanContext.create(TRACE_ID, SPAN_ID, "0000000000000000", true,
                TraceFlags.sampled(), TraceState.getDefault());
        Span span = Span.wrap(ctx);

        Scope scope = Context.makeCurrent(span);
        scope.close(); // scope 종료 후 record

        histogram.record(100L); // 이 시점에 Context.currentSpan()은 invalid

        List<Exemplar> exemplars = histogram.collectAndResetExemplars();
        assertTrue(exemplars.isEmpty(),
                "scope.close() 후 record하면 Exemplar가 캡처되면 안 됨 — JDBC advice 버그 재현");
    }

    // ── 기본 메트릭 정확성 ────────────────────────────────────────────────────

    @Test
    void basicCountSumMinMax() {
        histogram.record(10L);
        histogram.record(50L);
        histogram.record(200L);

        assertEquals(3L, histogram.getCount());
        assertEquals(260L, histogram.getSum());
        assertEquals(10L, histogram.getMin());
        assertEquals(200L, histogram.getMax());
    }

    @Test
    void bucketCountsLength() {
        histogram.record(5L);
        long[] counts = histogram.getBucketCounts();
        // boundaries.length + 1 (overflow slot)
        assertEquals(ExplicitBucketHistogram.DEFAULT_BOUNDARIES.length + 1, counts.length);
    }

    @Test
    void overflowBucketCounts() {
        // DEFAULT_BOUNDARIES 마지막 값(10000ms) 초과
        histogram.record(20000L);

        long[] counts = histogram.getBucketCounts();
        assertEquals(1L, counts[counts.length - 1], "overflow 버킷 카운트가 1이어야 함");
    }

    @Test
    void percentileEstimation() {
        for (int i = 0; i < 100; i++) {
            histogram.record(100L); // 100ms 버킷에 100개
        }
        long p50 = histogram.estimatePercentile(50.0);
        assertTrue(p50 > 0, "P50은 0보다 커야 함");
    }
}
