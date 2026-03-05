package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanId;
import com.agent.span.SpanKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SamplerTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    // ─── AlwaysOnSampler ────────────────────────────────────────────────────

    @Test
    void alwaysOnAlwaysSamples() {
        Sampler s = Sampler.alwaysOn();
        SamplingDecision d = s.shouldSample(null, TRACE_ID, "op", SpanKind.INTERNAL);
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, d);
    }

    // ─── AlwaysOffSampler ───────────────────────────────────────────────────

    @Test
    void alwaysOffAlwaysDrops() {
        Sampler s = Sampler.alwaysOff();
        SamplingDecision d = s.shouldSample(null, TRACE_ID, "op", SpanKind.INTERNAL);
        assertEquals(SamplingDecision.DROP, d);
    }

    // ─── ParentBasedSampler ─────────────────────────────────────────────────

    @Test
    void parentBasedNoParentDelegatesToRoot() {
        // rootSampler = alwaysOff → DROP
        Sampler s = Sampler.parentBased(Sampler.alwaysOff());
        SamplingDecision d = s.shouldSample(null, TRACE_ID, "op", SpanKind.INTERNAL);
        assertEquals(SamplingDecision.DROP, d);
    }

    @Test
    void parentBasedInvalidParentDelegatesToRoot() {
        Sampler s = Sampler.parentBased(Sampler.alwaysOn());
        SamplingDecision d = s.shouldSample(SpanContext.invalid(), TRACE_ID, "op", SpanKind.INTERNAL);
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, d);
    }

    @Test
    void parentBasedSampledParentPropagatesSample() {
        SpanContext sampledParent = SpanContext.create(
                TRACE_ID, "b7ad6b7169203331", SpanId.getInvalid(), true);
        Sampler s = Sampler.parentBased(Sampler.alwaysOff()); // root=OFF이지만 부모가 sampled
        SamplingDecision d = s.shouldSample(sampledParent, TRACE_ID, "op", SpanKind.INTERNAL);
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, d);
    }

    @Test
    void parentBasedNotSampledParentDrops() {
        SpanContext unsampled = SpanContext.create(
                TRACE_ID, "b7ad6b7169203331", SpanId.getInvalid(), false);
        Sampler s = Sampler.parentBased(Sampler.alwaysOn()); // root=ON이지만 부모가 unsampled
        SamplingDecision d = s.shouldSample(unsampled, TRACE_ID, "op", SpanKind.INTERNAL);
        assertEquals(SamplingDecision.DROP, d);
    }

    // ─── TraceIdRatioBasedSampler ────────────────────────────────────────────

    @Test
    void ratio100PercentAlwaysSamples() {
        Sampler s = Sampler.traceIdRatioBased(1.0);
        for (int i = 0; i < 10; i++) {
            String tid = generateTraceId(i);
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                    s.shouldSample(null, tid, "op", SpanKind.INTERNAL),
                    "100%는 항상 샘플링");
        }
    }

    @Test
    void ratio0PercentAlwaysDrops() {
        Sampler s = Sampler.traceIdRatioBased(0.0);
        for (int i = 0; i < 10; i++) {
            String tid = generateTraceId(i);
            assertEquals(SamplingDecision.DROP,
                    s.shouldSample(null, tid, "op", SpanKind.INTERNAL),
                    "0%는 항상 드롭");
        }
    }

    @Test
    void samplingDecisionHasAllThreeValues() {
        // RECORD_ONLY 추가 검증
        SamplingDecision[] values = SamplingDecision.values();
        assertEquals(3, values.length);
        assertNotNull(SamplingDecision.DROP);
        assertNotNull(SamplingDecision.RECORD_ONLY);
        assertNotNull(SamplingDecision.RECORD_AND_SAMPLE);
    }

    private String generateTraceId(int seed) {
        return String.format("%032x", (long) seed * 7919 + 1234567890L);
    }
}
