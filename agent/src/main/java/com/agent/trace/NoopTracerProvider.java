package com.agent.trace;

/**
 * 비활성 상태에서 사용하는 no-op TracerProvider.
 * TracerProvider.noop()으로 접근한다.
 */
final class NoopTracerProvider implements TracerProvider {
    static final NoopTracerProvider INSTANCE = new NoopTracerProvider();

    private NoopTracerProvider() {}

    @Override
    public Tracer getTracer(String instrumentationName) {
        return NoopTracer.INSTANCE;
    }

    @Override
    public TracerBuilder tracerBuilder(String instrumentationName) {
        return new NoopTracerBuilder();
    }
}
