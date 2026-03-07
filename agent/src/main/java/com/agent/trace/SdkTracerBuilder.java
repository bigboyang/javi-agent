package com.agent.trace;

/**
 * SDK 기본 TracerBuilder 구현체.
 */
public class SdkTracerBuilder implements TracerBuilder {
    private final SdkTracerProvider provider;
    private final String instrumentationName;
    private String instrumentationVersion;
    private String schemaUrl;

    public SdkTracerBuilder(SdkTracerProvider provider, String instrumentationName) {
        this.provider = provider;
        this.instrumentationName = instrumentationName;
    }

    @Override
    public TracerBuilder setSchemaUrl(String schemaUrl) {
        this.schemaUrl = schemaUrl;
        return this;
    }

    @Override
    public TracerBuilder setInstrumentationVersion(String instrumentationVersion) {
        this.instrumentationVersion = instrumentationVersion;
        return this;
    }

    @Override
    public Tracer build() {
        if (!provider.getTracerConfig().isEnabled() || provider.isShutdown()) {
            return NoopTracer.INSTANCE;
        }
        InstrumentationScopeInfo info =
                new InstrumentationScopeInfo(instrumentationName, instrumentationVersion, schemaUrl);
        return provider.getOrCreateTracer(info);
    }
}
