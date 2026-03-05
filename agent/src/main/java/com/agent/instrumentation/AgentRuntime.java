package com.agent.instrumentation;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.config.AgentConfig;
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

        System.out.println("[javi-agent] service=" + config.getServiceName()
                + " endpoint=" + config.getExporterEndpoint()
                + " sampleRate=" + config.getSampleRate());
    }

    private AgentRuntime() {}

    public static Tracer tracer() {
        return TRACER;
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
                System.err.println("[javi-agent] OTLP exporter 초기화 실패, 콘솔로 fallback: " + e.getMessage());
                return new LoggingSpanExporter();
            }
        }
        return new OtlpHttpSpanExporter(endpoint, config.getServiceName());
    }
}
