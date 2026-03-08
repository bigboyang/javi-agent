package com.agent.instrumentation;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.config.AgentConfig;
import com.agent.logs.AgentLogger;
import com.agent.logs.TraceLogger;
import com.agent.sampler.Sampler;
import com.agent.common.JaviSdk;
import com.agent.logs.FileLogExporter;
import com.agent.logs.SdkLoggerProvider;
import com.agent.metric.FileMetricExporter;
import com.agent.metric.SdkMeterProvider;
import com.agent.trace.SdkTracerProvider;
import com.agent.trace.Tracer;
import com.agent.trace.exporter.CompositeSpanExporter;
import com.agent.trace.exporter.LoggingSpanExporter;
import com.agent.trace.exporter.OtlpHttpSpanExporter;
import com.agent.trace.exporter.SpanExporter;
import java.util.Arrays;

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
        
        Sampler sampler;
        if (config.getTargetSps() > 0) {
            sampler = new com.agent.sampler.AdaptiveSampler(config.getTargetSps(), config.getSampleRate());
            AgentLogger.info("[AdaptiveSampler] Enabled (TargetSps: " + config.getTargetSps() + ")");
        } else {
            sampler = Sampler.traceIdRatioBased(config.getSampleRate());
        }

        PROVIDER = new SdkTracerProvider(IdGenerator.random());
        PROVIDER.setSampler(sampler);
        PROVIDER.addBatchSpanProcessor(exporter, 2048, 512, 5000);

        // JaviSdk 초기화 (Trace + Log + Metric 통합 관리)
        SdkLoggerProvider lp = new SdkLoggerProvider(new FileLogExporter());
        SdkMeterProvider mp = new SdkMeterProvider(new FileMetricExporter());
        JaviSdk.initialize(PROVIDER, lp, mp);

        TRACER = PROVIDER.getTracer("agent-auto");

        // MDC service 이름을 TraceLogger에 주입
        TraceLogger.setServiceName(config.getServiceName());

        AgentLogger.info("service=" + config.getServiceName()
                + " endpoint=" + config.getExporterEndpoint()
                + " sampleRate=" + config.getSampleRate());
        AgentLogger.info("trace 로그 파일: " + TraceLogger.logFilePath());
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
        LoggingSpanExporter loggingExporter = new LoggingSpanExporter();
        try {
            OtlpHttpSpanExporter otlpExporter = new OtlpHttpSpanExporter(endpoint, config.getServiceName());
            // Logging 먼저, OTLP 나중: Collector 없이도 로그에 스팬 출력됨
            // OtlpHttpSpanExporter는 연결 실패 시 재시도로 수 초간 블로킹되므로
            // LoggingSpanExporter를 앞에 두어 로그가 먼저 기록되도록 한다.
            return CompositeSpanExporter.create(Arrays.asList(loggingExporter, otlpExporter));
        } catch (Exception e) {
            AgentLogger.warn("OTLP exporter 초기화 실패, 콘솔로 fallback: " + e.getMessage());
            return loggingExporter;
        }
    }
}
