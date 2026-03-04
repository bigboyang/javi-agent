package com.agent.trace;

/**
 * Tracer를 제공하는 프로바이더 인터페이스.
 */
public interface TracerProvider {
    Tracer getTracer(String instrumentationName);
    TracerBuilder tracerBuilder(String instrumentationName);

    default Tracer getTracer(String instrumentationName, String instrumentationVersion) {
        return getTracer(instrumentationName);
    }
}
