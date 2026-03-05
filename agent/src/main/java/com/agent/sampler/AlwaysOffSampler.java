package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanKind;

/**
 * 모든 요청을 수집하지 않는다 (0%).
 * 헬스체크 엔드포인트나 테스트 환경 비활성화에 사용.
 */
final class AlwaysOffSampler implements Sampler {
    static final AlwaysOffSampler INSTANCE = new AlwaysOffSampler();

    private AlwaysOffSampler() {}

    @Override
    public SamplingDecision shouldSample(SpanContext parentContext, String traceId, String spanName, SpanKind spanKind) {
        return SamplingDecision.DROP;
    }
}
