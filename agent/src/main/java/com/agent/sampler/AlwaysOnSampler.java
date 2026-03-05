package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanKind;

/**
 * 모든 요청을 수집한다 (100%).
 */
final class AlwaysOnSampler implements Sampler {
    static final AlwaysOnSampler INSTANCE = new AlwaysOnSampler();

    private AlwaysOnSampler() {}

    @Override
    public SamplingDecision shouldSample(SpanContext parentContext, String traceId, String spanName, SpanKind spanKind) {
        return SamplingDecision.RECORD_AND_SAMPLE;
    }
}
