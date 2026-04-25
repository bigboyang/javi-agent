package com.agent.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 환경변수/시스템 프로퍼티에서 에이전트 설정을 읽는다. (Bootstrap Configuration)
 * 모든 전송은 OTLP/HTTP Protobuf 방식을 사용한다.
 */
public final class AgentConfig {
    public static final String DEFAULT_ENDPOINT = "http://localhost:4318";
    public static final String DEFAULT_SERVICE_NAME = "javi-service";
    public static final double DEFAULT_SAMPLE_RATE = 1.0;

    private static final AgentConfig INSTANCE = loadInternal();

    private final String exporterEndpoint;
    private final String serviceName;
    private final double sampleRate;
    private final long slowThresholdMs;
    private final List<String> criticalUrls;
    private final int clusterMinSamples;
    private final long targetSps;

    private AgentConfig(String exporterEndpoint, String serviceName, double sampleRate,
                        long slowThresholdMs, List<String> criticalUrls,
                        int clusterMinSamples, long targetSps) {
        this.exporterEndpoint = exporterEndpoint;
        this.serviceName = serviceName;
        this.sampleRate = sampleRate;
        this.slowThresholdMs = slowThresholdMs;
        this.criticalUrls = criticalUrls;
        this.clusterMinSamples = clusterMinSamples;
        this.targetSps = targetSps;
    }

    public static AgentConfig get() { return INSTANCE; }
    public static AgentConfig load() { return INSTANCE; }

    private static AgentConfig loadInternal() {
        String endpoint = read("JAVI_COLLECTOR_ENDPOINT", "javi.collector.endpoint", DEFAULT_ENDPOINT);
        String service = read("JAVI_SERVICE_NAME", "javi.service.name", DEFAULT_SERVICE_NAME);
        double rate = parseDouble(read("JAVI_SAMPLE_RATE", "javi.sample.rate", String.valueOf(DEFAULT_SAMPLE_RATE)));

        long slowThreshold = parseLong(read("JAVI_SLOW_THRESHOLD_MS", "javi.slow.threshold.ms", "500"));

        String urlsRaw = read("JAVI_CRITICAL_URLS", "javi.critical.urls", "");
        List<String> urls = Arrays.stream(urlsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        int minSamples = Integer.parseInt(read("JAVI_CLUSTER_MIN_SAMPLES", "javi.cluster.min.samples", "5"));
        long targetSps = parseLong(read("JAVI_SAMPLING_TARGET_SPS", "javi.sampling.target.sps", "0"));

        com.agent.logs.AgentLogger.info("[AgentConfig] OTLP/HTTP Protobuf 전송 설정 로드됨: endpoint=" + endpoint + ", service=" + service);
        
        return new AgentConfig(endpoint, service, rate, slowThreshold, urls, minSamples, targetSps);
    }

    public long getTargetSps() { return targetSps; }
    public int getClusterMinSamples() { return clusterMinSamples; }
    public List<String> getCriticalUrls() { return criticalUrls; }
    public String getExporterEndpoint() { return exporterEndpoint; }
    public String getServiceName() { return serviceName; }
    public double getSampleRate() { return sampleRate; }
    public long getSlowThresholdMs() { return slowThresholdMs; }

    private static String read(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return DEFAULT_SAMPLE_RATE; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 500L; }
    }
}
