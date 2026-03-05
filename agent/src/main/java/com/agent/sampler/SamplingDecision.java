package com.agent.sampler;

/**
 * 샘플링 결정 결과.
 */
public enum SamplingDecision {
    /** 수집하지 않는다. Span이 생성되지 않음. */
    DROP,
    /** 기록하지만 sampled 플래그를 세우지 않는다. 다운스트림에 전파되지 않음. */
    RECORD_ONLY,
    /** 수집하고 내보낸다. sampled 플래그 true. */
    RECORD_AND_SAMPLE
}
