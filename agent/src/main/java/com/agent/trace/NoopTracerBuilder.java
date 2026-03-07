package com.agent.trace;

/**
 * 비활성 상태에서 사용하는 no-op TracerBuilder.
 */
final class NoopTracerBuilder implements TracerBuilder {

    @Override
    public TracerBuilder setSchemaUrl(String schemaUrl) {
        return this;
    }

    @Override
    public TracerBuilder setInstrumentationVersion(String instrumentationVersion) {
        return this;
    }

    @Override
    public Tracer build() {
        return NoopTracer.INSTANCE;
    }
}
