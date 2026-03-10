package com.agent.common;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 공통 리소스 속성 수집기.
 *
 * <p>JVM 시작 시 1회 수집하여 모든 Exporter의 Resource 섹션에서 공유한다.
 * OTel Semantic Conventions Resource:
 * <ul>
 *   <li>service.name      - JAVI_SERVICE_NAME / javi.service.name</li>
 *   <li>host.name         - InetAddress.getLocalHost().getHostName()</li>
 *   <li>process.pid       - ProcessHandle.current().pid()</li>
 *   <li>os.type           - os.name 시스템 프로퍼티 기반</li>
 *   <li>deployment.environment - JAVI_DEPLOYMENT_ENV / javi.deployment.env</li>
 *   <li>telemetry.sdk.name     - "javi-agent"</li>
 *   <li>telemetry.sdk.language - "java"</li>
 *   <li>telemetry.sdk.version  - "1.0.0"</li>
 * </ul>
 */
public final class ResourceInfo {

    private static final Map<String, String> ATTRS;

    /** OTLP JSON/Protobuf 직렬화에 사용할 순서 보장 리스트. */
    private static final List<Map.Entry<String, String>> ATTRS_LIST;

    static {
        Map<String, String> m = new LinkedHashMap<>();

        // host.name
        try {
            m.put("host.name", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            m.put("host.name", "unknown");
        }

        // service.instance.id (멀티 인스턴스 구분 필수)
        m.put("service.instance.id", java.util.UUID.randomUUID().toString());

        // process.pid (Java 9+)
        try {
            m.put("process.pid", String.valueOf(ProcessHandle.current().pid()));
        } catch (Exception ignored) {}

        // os.type (OTel: "linux" | "darwin" | "windows" | ...)
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        if (osName.contains("win")) {
            m.put("os.type", "windows");
        } else if (osName.contains("mac")) {
            m.put("os.type", "darwin");
        } else {
            m.put("os.type", "linux");
        }

        // deployment.environment
        String env = get("JAVI_DEPLOYMENT_ENV", "javi.deployment.env", "");
        if (!env.isEmpty()) {
            m.put("deployment.environment", env);
        }

        // SDK metadata
        m.put("telemetry.sdk.name", "javi-agent");
        m.put("telemetry.sdk.language", "java");
        m.put("telemetry.sdk.version", "1.0.0");

        ATTRS = Collections.unmodifiableMap(m);
        ATTRS_LIST = Collections.unmodifiableList(new ArrayList<>(m.entrySet()));
    }

    private ResourceInfo() {}

    /** 모든 리소스 속성을 반환한다. */
    public static Map<String, String> getAttributes() {
        return ATTRS;
    }

    /** 순서 보장 entrySet (직렬화 최적화용). */
    public static List<Map.Entry<String, String>> getAttributeList() {
        return ATTRS_LIST;
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }
}
