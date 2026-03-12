package com.agent.instrumentation;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.config.AgentConfig;
import com.agent.config.RemoteConfigPoller;
import com.agent.logs.AgentLogger;
import com.agent.logs.TraceLogger;
import com.agent.sampler.AdaptiveSampler;
import com.agent.sampler.Sampler;
import com.agent.common.JaviSdk;
import com.agent.common.grpc.GrpcSender;
import com.agent.common.OtlpHttpSender;
import com.agent.logs.OtlpGrpcLogExporter;
import com.agent.logs.OtlpHttpLogExporter;
import com.agent.logs.SdkLoggerProvider;
import com.agent.metric.CompositeMetricExporter;
import com.agent.metric.FileMetricExporter;
import com.agent.metric.OtlpGrpcMetricExporter;
import com.agent.metric.OtlpHttpMetricExporter;
import com.agent.metric.SdkMeterProvider;
import com.agent.trace.SdkTracerProvider;
import com.agent.trace.Tracer;
import com.agent.trace.exporter.CompositeSpanExporter;
import com.agent.trace.exporter.LoggingSpanExporter;
import com.agent.trace.exporter.OtlpGrpcSpanExporter;
import com.agent.trace.exporter.OtlpHttpSpanExporter;
import com.agent.trace.exporter.SpanExporter;
import com.agent.trace.processor.TailSamplingSpanProcessor;
import java.util.Arrays;

/**
 * 에이전트 전역 TracerProvider를 보유한다.
 * AgentConfig에서 endpoint/serviceName/sampleRate를 읽어 초기화한다.
 */
public final class AgentRuntime {
    private static final SdkTracerProvider PROVIDER;
    private static final Tracer TRACER;

    static {
        AgentConfig config = AgentConfig.get();
        String protocol = config.getExporterProtocol().toLowerCase();

        SpanExporter spanExporter;
        SdkLoggerProvider loggerProvider;
        SdkMeterProvider meterProvider;

        // 성능 최적화: gRPC를 기본으로 사용 (JSON 직렬화 오버헤드 제거)
        if ("http".equals(protocol)) {
            // 명시적으로 HTTP가 설정된 경우에만 활성화
            OtlpHttpSender sharedHttp = OtlpHttpSender.create();
            spanExporter = buildHttpSpanExporter(config, sharedHttp);
            loggerProvider = new SdkLoggerProvider(new OtlpHttpLogExporter(config.getExporterEndpoint(), config.getServiceName(), sharedHttp));
            meterProvider = new SdkMeterProvider(
                    CompositeMetricExporter.of(
                            new FileMetricExporter(),
                            new OtlpHttpMetricExporter(config.getExporterEndpoint(), config.getServiceName(), sharedHttp)));
            AgentLogger.info("프로토콜: HTTP (JSON) endpoint=" + config.getExporterEndpoint());
        } else {
            // 기본값: gRPC (Protobuf)
            GrpcSender sharedGrpc = GrpcSender.create(config.getGrpcEndpoint(), 10_000L);
            spanExporter = buildGrpcSpanExporter(config, sharedGrpc);
            loggerProvider = new SdkLoggerProvider(new OtlpGrpcLogExporter(config.getServiceName(), sharedGrpc));
            meterProvider = new SdkMeterProvider(
                    CompositeMetricExporter.of(
                            new FileMetricExporter(),
                            new OtlpGrpcMetricExporter(config.getServiceName(), sharedGrpc)));
            AgentLogger.info("프로토콜: OTLP/HTTP Protobuf endpoint=" + config.getGrpcEndpoint());
        }

        // Fix 1: ParentBased — 항상 ParentBasedSampler로 감싸서 원격 부모의 sampled 결정을 존중
        Sampler sampler;
        AdaptiveSampler adaptiveSampler = null;
        if (config.getTargetSps() > 0) {
            adaptiveSampler = new AdaptiveSampler(config.getTargetSps(), config.getSampleRate());
            sampler = Sampler.parentBased(adaptiveSampler);
            AgentLogger.info("[AdaptiveSampler] Enabled (TargetSps: " + config.getTargetSps() + "), wrapped in ParentBasedSampler");
        } else {
            sampler = Sampler.parentBased(Sampler.traceIdRatioBased(config.getSampleRate()));
        }

        PROVIDER = new SdkTracerProvider(IdGenerator.random());
        PROVIDER.setSampler(sampler);

        // Fix 2: True Tail Sampling — traceId 단위로 버퍼링, 루트 스팬 완료 시 트레이스 전체 평가
        if (config.isTailSamplingEnabled()) {
            PROVIDER.addSpanProcessor(new TailSamplingSpanProcessor(spanExporter, config));
            AgentLogger.info("[TailSamplingSpanProcessor] Enabled (TTL=" + config.getTailSamplingTtlMs() + "ms)");
        } else {
            PROVIDER.addBatchSpanProcessor(spanExporter, 2048, 512, 5000);
        }

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
        AgentLogger.info(com.agent.logs.LogSampler.INSTANCE.describe());
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

    private static SpanExporter buildGrpcSpanExporter(AgentConfig config, GrpcSender grpcSender) {
        LoggingSpanExporter loggingExporter = new LoggingSpanExporter();
        try {
            SpanExporter otlpExporter = new OtlpGrpcSpanExporter(config.getServiceName(), grpcSender);
            // LoggingSpanExporter를 앞에 두어 Collector 없이도 로그에 스팬이 출력됨
            return CompositeSpanExporter.create(Arrays.asList(loggingExporter, otlpExporter));
        } catch (Exception e) {
            AgentLogger.warn("OTLP gRPC exporter 초기화 실패, 콘솔로 fallback: " + e.getMessage());
            return loggingExporter;
        }
    }

    private static SpanExporter buildHttpSpanExporter(AgentConfig config, OtlpHttpSender httpSender) {
        LoggingSpanExporter loggingExporter = new LoggingSpanExporter();
        try {
            SpanExporter otlpExporter = new OtlpHttpSpanExporter(config.getExporterEndpoint(), config.getServiceName(), httpSender);
            // LoggingSpanExporter를 앞에 두어 Collector 없이도 로그에 스팬이 출력됨
            return CompositeSpanExporter.create(Arrays.asList(loggingExporter, otlpExporter));
        } catch (Exception e) {
            AgentLogger.warn("OTLP HTTP exporter 초기화 실패, 콘솔로 fallback: " + e.getMessage());
            return loggingExporter;
        }
    }
}
