package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanKind;

/**
 * 부모 Span의 sampled 결정을 따르는 샘플러.
 *
 * 분산 환경에서 업스트림이 수집하기로 결정했으면 다운스트림도 수집,
 * 업스트림이 드롭했으면 다운스트림도 드롭하여 트레이스 일관성을 보장한다.
 *
 * 루트 스팬(부모 없음)은 rootSampler로 결정한다.
 */
public final class ParentBasedSampler implements Sampler {
    private final Sampler rootSampler;

    public ParentBasedSampler(Sampler rootSampler) {
        this.rootSampler = rootSampler == null ? Sampler.alwaysOn() : rootSampler;
    }

    @Override
    public SamplingDecision shouldSample(SpanContext parent, String traceId, String spanName, SpanKind spanKind) {
        if (parent == null || !parent.isValid()) {
            // 루트 스팬: rootSampler로 결정
            return rootSampler.shouldSample(parent, traceId, spanName, spanKind);
        }
        // 부모 결정을 그대로 따름
        return parent.isSampled() ? SamplingDecision.RECORD_AND_SAMPLE : SamplingDecision.DROP;
    }
}
