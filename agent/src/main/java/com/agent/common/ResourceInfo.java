package com.agent.common;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 에이전트 공통 리소스 속성 수집기.
 *
 * <p>JVM 시작 시 1회 수집하여 모든 Exporter의 Resource 섹션에서 공유한다.
 * OTel Semantic Conventions Resource:
 * <ul>
 *   <li>service.name      - JAVI_SERVICE_NAME / javi.service.name</li>
 *   <li>host.name         - InetAddress.getLocalHost().getHostName()</li>
 *   <li>host.fqdn         - InetAddress.getLocalHost().getCanonicalHostName()</li>
 *   <li>process.pid       - ProcessHandle.current().pid()</li>
 *   <li>os.type           - os.name 시스템 프로퍼티 기반</li>
 *   <li>deployment.environment.name - JAVI_DEPLOYMENT_ENV / javi.deployment.env</li>
 *   <li>telemetry.sdk.name     - "javi-agent"</li>
 *   <li>telemetry.sdk.language - "java"</li>
 *   <li>telemetry.sdk.version  - "1.0.0"</li>
 *   <li>k8s.pod.name / k8s.namespace / k8s.node.name - 환경변수 및 자동 감지</li>
 *   <li>container.id      - /proc/self/cgroup 또는 /proc/self/mountinfo에서 추출</li>
 * </ul>
 */
public final class ResourceInfo {

    private static final Map<String, String> ATTRS;

    /** OTLP JSON/Protobuf 직렬화에 사용할 순서 보장 리스트. */
    private static final List<Map.Entry<String, String>> ATTRS_LIST;

    static {
        Map<String, String> m = new LinkedHashMap<>();

        // service.name (OTel 필수 속성 — 모든 exporter가 이를 공유)
        String serviceName = get("JAVI_SERVICE_NAME", "javi.service.name", "javi-service");
        m.put("service.name", serviceName);

        // service.version
        String serviceVersion = get("JAVI_SERVICE_VERSION", "javi.service.version", "");
        if (!serviceVersion.isEmpty()) {
            m.put("service.version", serviceVersion);
        }

        // service.instance.id (멀티 인스턴스 구분 필수)
        m.put("service.instance.id", java.util.UUID.randomUUID().toString());

        // host.name & host.fqdn
        detectHost(m);

        // host.arch (amd64, arm64, etc.)
        String osArch = System.getProperty("os.arch", "");
        if (!osArch.isEmpty()) {
            // OTel convention: x86_64 → amd64
            if ("x86_64".equals(osArch)) osArch = "amd64";
            else if ("aarch64".equals(osArch)) osArch = "arm64";
            m.put("host.arch", osArch);
        }

        // process.pid (Java 9+)
        try {
            m.put("process.pid", String.valueOf(ProcessHandle.current().pid()));
        } catch (Exception ignored) {}

        // process.runtime.*
        String runtimeName = System.getProperty("java.runtime.name", "");
        if (!runtimeName.isEmpty()) m.put("process.runtime.name", runtimeName);
        String runtimeVersion = System.getProperty("java.runtime.version", "");
        if (!runtimeVersion.isEmpty()) m.put("process.runtime.version", runtimeVersion);
        String vmName = System.getProperty("java.vm.name", "");
        String vmVersion = System.getProperty("java.vm.version", "");
        if (!vmName.isEmpty() && !vmVersion.isEmpty()) {
            m.put("process.runtime.description", vmName + " (" + vmVersion + ")");
        }

        // os.type + os.description
        detectOs(m);

        // deployment.environment.name (OTel 1.24+)
        String env = get("JAVI_DEPLOYMENT_ENV", "javi.deployment.env", "");
        if (!env.isEmpty()) {
            m.put("deployment.environment.name", env);
        }

        // Kubernetes Resource Detection
        detectKubernetes(m);

        // Container ID Detection
        detectContainer(m);

        // SDK metadata
        m.put("telemetry.sdk.name", "javi-agent");
        m.put("telemetry.sdk.language", "java");
        m.put("telemetry.sdk.version", "1.0.0");
        m.put("telemetry.auto.version", "1.0.0");

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

    private static void detectHost(Map<String, String> m) {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            String hostName = localHost.getHostName();
            String fqdn = localHost.getCanonicalHostName();

            // InetAddress가 localhost 등 의미 없는 값을 반환할 경우 환경변수 확인
            if ("localhost".equalsIgnoreCase(hostName) || "127.0.0.1".equals(hostName)) {
                String envHost = System.getenv("HOSTNAME");
                if (envHost != null && !envHost.isEmpty()) hostName = envHost;
            }

            m.put("host.name", hostName);
            if (fqdn != null && !fqdn.isEmpty() && !fqdn.equals(hostName)) {
                m.put("host.fqdn", fqdn);
            }
        } catch (Exception e) {
            m.put("host.name", "unknown");
        }
    }

    private static void detectOs(Map<String, String> m) {
        String osName = System.getProperty("os.name", "unknown");
        String osNameLower = osName.toLowerCase();
        if (osNameLower.contains("win")) {
            m.put("os.type", "windows");
        } else if (osNameLower.contains("mac")) {
            m.put("os.type", "darwin");
        } else {
            m.put("os.type", "linux");
        }
        String osVersion = System.getProperty("os.version", "");
        if (!osName.isEmpty() && !osVersion.isEmpty()) {
            m.put("os.description", osName + " " + osVersion);
        }
    }

    private static void detectKubernetes(Map<String, String> m) {
        // Pod Name — OTel: k8s.pod.name
        String podName = System.getenv("K8S_POD_NAME");
        if (podName != null && !podName.isEmpty()) {
            m.put("k8s.pod.name", podName);
        }

        // Pod UID — OTel: k8s.pod.uid (Downward API: metadata.uid)
        String podUid = System.getenv("K8S_POD_UID");
        if (podUid != null && !podUid.isEmpty()) {
            m.put("k8s.pod.uid", podUid);
        }

        // Namespace — OTel 표준명: k8s.namespace.name (k8s.namespace는 비표준)
        String namespace = System.getenv("K8S_NAMESPACE");
        if (namespace == null || namespace.isEmpty()) {
            // Fallback: ServiceAccount 파일에서 읽기
            namespace = readFileContents("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
        }
        if (namespace != null && !namespace.isEmpty()) {
            m.put("k8s.namespace.name", namespace.trim());
        }

        // Node Name — OTel: k8s.node.name (Downward API: spec.nodeName)
        String nodeName = System.getenv("K8S_NODE_NAME");
        if (nodeName != null && !nodeName.isEmpty()) {
            m.put("k8s.node.name", nodeName);
        }

        // Deployment Name — Infra Metrics Correlation JOIN 키
        // Downward API 미지원 → 환경변수로 직접 주입 필요
        // e.g. valueFrom.fieldRef.fieldPath: metadata.labels['app']
        String deploymentName = System.getenv("K8S_DEPLOYMENT_NAME");
        if (deploymentName != null && !deploymentName.isEmpty()) {
            m.put("k8s.deployment.name", deploymentName);
        }

        // ReplicaSet Name — OTel: k8s.replicaset.name
        String replicaSetName = System.getenv("K8S_REPLICASET_NAME");
        if (replicaSetName != null && !replicaSetName.isEmpty()) {
            m.put("k8s.replicaset.name", replicaSetName);
        }
    }

    private static void detectContainer(Map<String, String> m) {
        String containerId = detectContainerIdFromCgroup();
        if (containerId == null || containerId.isEmpty()) {
            containerId = detectContainerIdFromMountInfo();
        }

        if (containerId != null && !containerId.isEmpty()) {
            m.put("container.id", containerId);
        }
    }

    /**
     * /proc/self/cgroup 파일에서 컨테이너 ID를 추출한다. (cgroup v1용)
     */
    private static String detectContainerIdFromCgroup() {
        String cgroupFile = "/proc/self/cgroup";
        try (BufferedReader br = new BufferedReader(new FileReader(cgroupFile))) {
            String line;
            Pattern dockerIdPattern = Pattern.compile("^[0-9a-f]{64}$");
            while ((line = br.readLine()) != null) {
                // 예: 11:name=systemd:/docker/76595...
                String[] parts = line.split("/");
                for (String part : parts) {
                    if (part.length() == 64) {
                        Matcher m = dockerIdPattern.matcher(part);
                        if (m.matches()) return part;
                    }
                    // kubernetes: .../pod<uid>/<containerid>
                    if (part.endsWith(".scope")) {
                        String id = part.substring(0, part.length() - 6);
                        if (id.contains("-")) id = id.substring(id.lastIndexOf("-") + 1);
                        if (id.length() == 64 && dockerIdPattern.matcher(id).matches()) return id;
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * /proc/self/mountinfo 파일에서 컨테이너 ID를 추출한다. (cgroup v2용)
     */
    private static String detectContainerIdFromMountInfo() {
        String mountFile = "/proc/self/mountinfo";
        try (BufferedReader br = new BufferedReader(new FileReader(mountFile))) {
            String line;
            Pattern dockerIdPattern = Pattern.compile("[0-9a-f]{64}");
            while ((line = br.readLine()) != null) {
                Matcher matcher = dockerIdPattern.matcher(line);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String readFileContents(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
