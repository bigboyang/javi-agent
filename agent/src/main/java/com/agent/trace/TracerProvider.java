package com.agent.trace;

import com.agent.common.utils.concurrent.CompletableResultCode;

/**
 * Tracer를 제공하는 프로바이더 인터페이스.
 */
public interface TracerProvider {
    Tracer getTracer(String instrumentationName);
    TracerBuilder tracerBuilder(String instrumentationName);

    default Tracer getTracer(String instrumentationName, String instrumentationVersion) {
        return tracerBuilder(instrumentationName)
                .setInstrumentationVersion(instrumentationVersion)
                .build();
    }

    default CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    default CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    static TracerProvider noop() {
        return NoopTracerProvider.INSTANCE;
    }
}
