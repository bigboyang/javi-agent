package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanKind;

/**
 * traceId 하위 비트 기반 확률 샘플링.
 * 동일 traceId는 분산 서비스 간에도 항상 동일한 결정을 내린다.
 */
final class TraceIdRatioBasedSampler implements Sampler {
    private final long idUpperBound;

    TraceIdRatioBasedSampler(double ratio) {
        if (ratio <= 0.0) {
            this.idUpperBound = Long.MIN_VALUE;
        } else if (ratio >= 1.0) {
            this.idUpperBound = Long.MAX_VALUE;
        } else {
            this.idUpperBound = (long) (ratio * Long.MAX_VALUE);
        }
    }

    @Override
    public SamplingDecision shouldSample(SpanContext parentContext, String traceId, String spanName, SpanKind spanKind) {
        if (traceId == null || traceId.length() < 16) {
            return SamplingDecision.DROP;
        }
        // traceId 하위 16자 (8바이트)를 long으로 변환해 비율 결정
        try {
            long low = Long.parseUnsignedLong(traceId.substring(traceId.length() - 16), 16);
            long abs = low < 0 ? ~low : low;
            return abs <= idUpperBound ? SamplingDecision.RECORD_AND_SAMPLE : SamplingDecision.DROP;
        } catch (NumberFormatException e) {
            return SamplingDecision.RECORD_AND_SAMPLE;
        }
    }
}
