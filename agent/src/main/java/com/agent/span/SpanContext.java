package com.agent.span;

import com.agent.trace.TraceId;

/**
 * 스팬의 식별 정보와 샘플링 여부를 담는 컨텍스트.
 */
public final class SpanContext {
    private static final SpanContext INVALID =
            new SpanContext(
                    TraceId.getInvalid(),
                    SpanId.getInvalid(),
                    SpanId.getInvalid(),
                    false,
                    TraceFlags.getDefault(),
                    TraceState.getDefault());

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final boolean sampled;
    private final TraceFlags traceFlags;
    private final TraceState traceState;

    private SpanContext(
            String traceId,
            String spanId,
            String parentSpanId,
            boolean sampled,
            TraceFlags traceFlags,
            TraceState traceState) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.sampled = sampled;
        this.traceFlags = traceFlags;
        this.traceState = traceState;
    }

    public static SpanContext create(String traceId, String spanId, String parentSpanId, boolean sampled) {
        TraceFlags flags = sampled ? TraceFlags.sampled() : TraceFlags.getDefault();
        return new SpanContext(traceId, spanId, parentSpanId, sampled, flags, TraceState.getDefault());
    }

    public static SpanContext create(
            String traceId,
            String spanId,
            String parentSpanId,
            boolean sampled,
            TraceFlags traceFlags,
            TraceState traceState) {
        return new SpanContext(traceId, spanId, parentSpanId, sampled, traceFlags, traceState);
    }

    public static SpanContext invalid() {
        return INVALID;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public boolean isSampled() {
        return sampled;
    }

    public TraceFlags getTraceFlags() {
        return traceFlags;
    }

    public TraceState getTraceState() {
        return traceState;
    }

    public boolean isValid() {
        return TraceId.isValid(traceId) && SpanId.isValid(spanId);
    }
}
