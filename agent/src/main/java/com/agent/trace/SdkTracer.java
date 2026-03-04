package com.agent.trace;

import com.agent.span.NoopSpanBuilder;
import com.agent.span.SdkSpanBuilder;
import com.agent.span.SpanBuilder;

/**
 * SDK 기본 Tracer 구현체.
 */
public class SdkTracer implements Tracer {
    private static final String FALLBACK_SPAN_NAME = "<unspecified span name>";

    private final TracerSharedState sharedState;
    private final InstrumentationScopeInfo instrumentationScopeInfo;
    private volatile boolean tracerEnabled;

    public SdkTracer(
            TracerSharedState sharedState,
            InstrumentationScopeInfo instrumentationScopeInfo,
            TracerConfig tracerConfig) {
        this.sharedState = sharedState;
        this.instrumentationScopeInfo = instrumentationScopeInfo;
        this.tracerEnabled = tracerConfig.isEnabled();
    }

    @Override
    public SpanBuilder spanBuilder(String name) {
        if (!tracerEnabled || sharedState.isShutdown()) {
            return NoopSpanBuilder.INSTANCE;
        }
        if (name == null || name.trim().isEmpty()) {
            name = FALLBACK_SPAN_NAME;
        }
        return new SdkSpanBuilder(name, sharedState, true);
    }

    public String getInstrumentationName() {
        return instrumentationScopeInfo.getName();
    }

    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return instrumentationScopeInfo;
    }

    public boolean isEnabled() {
        return tracerEnabled;
    }

    public void updateTracerConfig(TracerConfig tracerConfig) {
        this.tracerEnabled = tracerConfig.isEnabled();
    }
}
