package com.agent.instrumentation;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.config.AgentConfig;
import com.agent.config.RemoteConfigPoller;
import com.agent.logs.AgentLogger;
import com.agent.logs.TraceLogger;
import com.agent.sampler.AdaptiveSampler;
import com.agent.sampler.Sampler;
import com.agent.common.JaviSdk;
import com.agent.common.OtlpHttpProtobufSender;
import com.agent.logs.OtlpHttpLogExporter;
import com.agent.logs.SdkLoggerProvider;
import com.agent.metric.CompositeMetricExporter;
import com.agent.metric.FileMetricExporter;
import com.agent.metric.OtlpHttpMetricExporter;
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
        
        // 전송 방식 통일: OTLP/HTTP Protobuf
        OtlpHttpProtobufSender sharedSender = OtlpHttpProtobufSender.create(config.getExporterEndpoint(), 10_000L);
        
        SpanExporter spanExporter = buildSpanExporter(config, sharedSender);
        
        // 로그 및 메트릭 Exporter도 Protobuf Sender 공유
        SdkLoggerProvider loggerProvider = new SdkLoggerProvider(
                new OtlpHttpLogExporter(config.getServiceName(), sharedSender));
        
        SdkMeterProvider meterProvider = new SdkMeterProvider(
                CompositeMetricExporter.of(
                        new FileMetricExporter(),
                        new OtlpHttpMetricExporter(config.getServiceName(), sharedSender)));

        AgentLogger.info("전송 프로토콜: OTLP/HTTP Protobuf (endpoint=" + config.getExporterEndpoint() + ")");

        Sampler sampler;
        AdaptiveSampler adaptiveSampler = null;
        if (config.getTargetSps() > 0) {
            adaptiveSampler = new AdaptiveSampler(config.getTargetSps(), config.getSampleRate());
            sampler = adaptiveSampler;
            AgentLogger.info("[AdaptiveSampler] Enabled (TargetSps: " + config.getTargetSps() + ")");
        } else {
            sampler = Sampler.traceIdRatioBased(config.getSampleRate());
        }

        PROVIDER = new SdkTracerProvider(IdGenerator.random());
        PROVIDER.setSampler(sampler);
        PROVIDER.addBatchSpanProcessor(spanExporter, 2048, 512, 5000);

        // JaviSdk 초기화 (Trace + Log + Metric 통합 관리)
        JaviSdk.initialize(PROVIDER, loggerProvider, meterProvider);

        TRACER = PROVIDER.getTracer("agent-auto");

        // MDC service 이름을 TraceLogger에 주입
        TraceLogger.setServiceName(config.getServiceName());

        // 원격 설정 폴러 시작 (대시보드 pull 방식)
        RemoteConfigPoller poller = RemoteConfigPoller.startIfConfigured(PROVIDER);
        if (poller != null && adaptiveSampler != null) {
            poller.setAdaptiveSampler(adaptiveSampler);
        }

        AgentLogger.info("service=" + config.getServiceName()
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

    private static SpanExporter buildSpanExporter(AgentConfig config, OtlpHttpProtobufSender sender) {
        LoggingSpanExporter loggingExporter = new LoggingSpanExporter();
        try {
            SpanExporter otlpExporter = new OtlpHttpSpanExporter(config.getExporterEndpoint(), config.getServiceName(), sender);
            return CompositeSpanExporter.create(Arrays.asList(loggingExporter, otlpExporter));
        } catch (Exception e) {
            AgentLogger.warn("OTLP HTTP Protobuf exporter 초기화 실패, 콘솔로 fallback: " + e.getMessage());
            return loggingExporter;
        }
    }
}
