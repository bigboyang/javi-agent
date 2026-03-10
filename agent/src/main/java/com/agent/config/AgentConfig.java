package com.agent.config;

/**
 * 환경변수/시스템 프로퍼티에서 에이전트 설정을 읽는다.
 *
 * 환경변수 우선, 없으면 시스템 프로퍼티, 없으면 기본값 사용.
 */
public final class AgentConfig {
    public static final String DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces";
    public static final String DEFAULT_SERVICE_NAME = "javi-service";
    public static final double DEFAULT_SAMPLE_RATE = 1.0;

    private final String exporterEndpoint;
    private final String serviceName;
    private final double sampleRate;
    private final boolean tailSamplingEnabled;
    private final long slowThresholdMs;
    private final java.util.List<String> criticalUrls;
    private final int clusterMinSamples;
    private final long targetSps;
    private final String exporterProtocol;
    private final String grpcEndpoint;

    private AgentConfig(String exporterEndpoint, String serviceName, double sampleRate,
                        boolean tailSamplingEnabled, long slowThresholdMs,
                        java.util.List<String> criticalUrls, int clusterMinSamples, long targetSps,
                        String exporterProtocol, String grpcEndpoint) {
        this.exporterEndpoint = exporterEndpoint;
        this.serviceName = serviceName;
        this.sampleRate = sampleRate;
        this.tailSamplingEnabled = tailSamplingEnabled;
        this.slowThresholdMs = slowThresholdMs;
        this.criticalUrls = criticalUrls;
        this.clusterMinSamples = clusterMinSamples;
        this.targetSps = targetSps;
        this.exporterProtocol = exporterProtocol;
        this.grpcEndpoint = grpcEndpoint;
    }

    public static AgentConfig load() {
        String endpoint = get("JAVI_EXPORTER_ENDPOINT", "javi.exporter.endpoint", DEFAULT_ENDPOINT);
        String service = get("JAVI_SERVICE_NAME", "javi.service.name", DEFAULT_SERVICE_NAME);
        double rate = parseDouble(get("JAVI_SAMPLE_RATE", "javi.sample.rate", String.valueOf(DEFAULT_SAMPLE_RATE)));

        boolean tailEnabled = Boolean.parseBoolean(get("JAVI_TAIL_SAMPLING_ENABLED", "javi.tail.sampling.enabled", "true"));
        long slowThreshold = parseLong(get("JAVI_SLOW_THRESHOLD_MS", "javi.slow.threshold.ms", "500"));

        String urlsRaw = get("JAVI_CRITICAL_URLS", "javi.critical.urls", "");
        java.util.List<String> urls = java.util.Arrays.stream(urlsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());

        int minSamples = Integer.parseInt(get("JAVI_CLUSTER_MIN_SAMPLES", "javi.cluster.min.samples", "5"));
        long targetSps = parseLong(get("JAVI_SAMPLING_TARGET_SPS", "javi.sampling.target.sps", "0"));

        String protocol = get("JAVI_EXPORTER_PROTOCOL", "javi.exporter.protocol", "http");
        String grpcEndpoint = get("JAVI_GRPC_ENDPOINT", "javi.grpc.endpoint", "http://localhost:4317");

        return new AgentConfig(endpoint, service, rate, tailEnabled, slowThreshold, urls, minSamples,
                targetSps, protocol, grpcEndpoint);
    }

    public long getTargetSps() {
        return targetSps;
    }

    /** 익스포터 프로토콜. "grpc" 또는 "http" (기본값: "http"). */
    public String getExporterProtocol() {
        return exporterProtocol;
    }

    /** gRPC Collector 기본 URL (기본값: http://localhost:4317). */
    public String getGrpcEndpoint() {
        return grpcEndpoint;
    }

    public int getClusterMinSamples() {
        return clusterMinSamples;
    }

    public java.util.List<String> getCriticalUrls() {
        return criticalUrls;
    }

    public String getExporterEndpoint() {
        return exporterEndpoint;
    }

    public String getServiceName() {
        return serviceName;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public boolean isTailSamplingEnabled() {
        return tailSamplingEnabled;
    }

    public long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return DEFAULT_SAMPLE_RATE;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 500L;
        }
    }
}
