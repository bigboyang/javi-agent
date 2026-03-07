package com.agent.instrumentation;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.config.AgentConfig;
import com.agent.logs.AgentLogger;
import com.agent.sampler.Sampler;
import com.agent.trace.SdkTracerProvider;
import com.agent.trace.Tracer;
import com.agent.trace.exporter.LoggingSpanExporter;
import com.agent.trace.exporter.OtlpHttpSpanExporter;
import com.agent.trace.exporter.SpanExporter;

/**
 * 에이전트 전역 TracerProvider를 보유한다.
 * AgentConfig에서 endpoint/serviceName/sampleRate를 읽어 초기화한다.
 */
public final class AgentRuntime {
    private static final SdkTracerProvider PROVIDER;
    private static final Tracer TRACER;

    static {
        AgentConfig config = AgentConfig.load();

        SpanExporter exporter = buildExporter(config);
        Sampler sampler = Sampler.traceIdRatioBased(config.getSampleRate());

        PROVIDER = new SdkTracerProvider(IdGenerator.random());
        PROVIDER.setSampler(sampler);
        PROVIDER.addBatchSpanProcessor(exporter, 2048, 512, 5000);

        TRACER = PROVIDER.getTracer("agent-auto");

        AgentLogger.info("service=" + config.getServiceName()
                + " endpoint=" + config.getExporterEndpoint()
                + " sampleRate=" + config.getSampleRate());
    }

    private AgentRuntime() {}

    /** 기본 agent-auto 스코프 Tracer를 반환한다. */
    public static Tracer tracer() {
        return TRACER;
    }

    /**
     * 지정한 instrumentationName 스코프의 Tracer를 반환한다.
     * 각 계측 모듈(HTTP, JDBC, Spring 등)은 자신만의 이름으로 Tracer를 획득해야 한다.
     *
     * <pre>{@code
     * Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");
     * }</pre>
     */
    public static Tracer getTracer(String instrumentationName) {
        return PROVIDER.getTracer(instrumentationName);
    }

    /**
     * 버전 정보를 포함한 Tracer를 반환한다.
     */
    public static Tracer getTracer(String instrumentationName, String version) {
        return PROVIDER.tracerBuilder(instrumentationName)
                .setInstrumentationVersion(version)
                .build();
    }

    public static SdkTracerProvider provider() {
        return PROVIDER;
    }

    private static SpanExporter buildExporter(AgentConfig config) {
        String endpoint = config.getExporterEndpoint();
        if (endpoint.equals(AgentConfig.DEFAULT_ENDPOINT)) {
            // OTel Collector 미설정 시 콘솔 fallback
            try {
                return new OtlpHttpSpanExporter(endpoint, config.getServiceName());
            } catch (Exception e) {
                AgentLogger.warn("OTLP exporter 초기화 실패, 콘솔로 fallback: " + e.getMessage());
                return new LoggingSpanExporter();
            }
        }
        return new OtlpHttpSpanExporter(endpoint, config.getServiceName());
    }
}
