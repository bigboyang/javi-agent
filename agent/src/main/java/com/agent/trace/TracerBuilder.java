package com.agent.trace;

/**
 * Tracer 생성 설정을 위한 빌더 인터페이스.
 */
public interface TracerBuilder {
    TracerBuilder setSchemaUrl(String schemaUrl);

    TracerBuilder setInstrumentationVersion(String instrumentationVersion);

    Tracer build();
}
