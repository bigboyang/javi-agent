package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanKind;

/**
 * Span 샘플링 여부를 결정하는 인터페이스.
 */
public interface Sampler {
    SamplingDecision shouldSample(SpanContext parentContext, String traceId, String spanName, SpanKind spanKind);

    static Sampler alwaysOn() {
        return AlwaysOnSampler.INSTANCE;
    }

    static Sampler alwaysOff() {
        return AlwaysOffSampler.INSTANCE;
    }

    static Sampler traceIdRatioBased(double ratio) {
        return new TraceIdRatioBasedSampler(ratio);
    }

    static Sampler parentBased(Sampler rootSampler) {
        return new ParentBasedSampler(rootSampler);
    }
}
