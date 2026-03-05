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

    private AgentConfig(String exporterEndpoint, String serviceName, double sampleRate) {
        this.exporterEndpoint = exporterEndpoint;
        this.serviceName = serviceName;
        this.sampleRate = sampleRate;
    }

    public static AgentConfig load() {
        String endpoint = get("JAVI_EXPORTER_ENDPOINT", "javi.exporter.endpoint", DEFAULT_ENDPOINT);
        String service = get("JAVI_SERVICE_NAME", "javi.service.name", DEFAULT_SERVICE_NAME);
        double rate = parseDouble(get("JAVI_SAMPLE_RATE", "javi.sample.rate", String.valueOf(DEFAULT_SAMPLE_RATE)));
        return new AgentConfig(endpoint, service, rate);
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
}
