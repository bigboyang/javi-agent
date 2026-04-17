package com.agent.metric;

import com.agent.common.ResourceInfo;
import com.agent.config.AgentConfig;
import com.agent.logs.AgentLogger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pod 내부 cgroup 파일을 읽어 K8s 리소스 메트릭을 수집하고
 * {@code POST /api/collector/k8s-metrics} 로 전송한다.
 *
 * <p>수집 항목:
 * <ul>
 *   <li>cpu_usage_millicore  — cgroup CPU 사용량 (millicores, rate 계산)</li>
 *   <li>cpu_limit_millicore  — cgroup CPU 한도 (0 = unlimited)</li>
 *   <li>memory_usage_bytes   — cgroup 메모리 사용량</li>
 *   <li>memory_limit_bytes   — cgroup 메모리 한도 (0 = unlimited)</li>
 *   <li>memory_rss_bytes     — RSS (페이지 캐시 제외, 실제 물리 메모리)</li>
 * </ul>
 *
 * <p>cgroup 버전 자동 감지:
 * <ul>
 *   <li>v1: {@code /sys/fs/cgroup/cpu/cpuacct.usage}, {@code memory.usage_in_bytes} 등</li>
 *   <li>v2: {@code /sys/fs/cgroup/cpu.stat}, {@code memory.current} 등</li>
 * </ul>
 *
 * <p>환경변수:
 * <ul>
 *   <li>{@code JAVI_K8S_METRICS_ENABLED} — "false"이면 비활성화 (기본: true)</li>
 *   <li>{@code JAVI_K8S_METRICS_INTERVAL_SEC} — 수집 주기 초 (기본: 15)</li>
 *   <li>{@code JAVI_PROFILING_API} — 컬렉터 API 기본 URL (ProfilingExporter와 동일 설정 재사용)</li>
 * </ul>
 */
public final class K8sMetricsCollector {

    private static final String ENV_ENABLED      = "JAVI_K8S_METRICS_ENABLED";
    private static final String ENV_INTERVAL_SEC = "JAVI_K8S_METRICS_INTERVAL_SEC";
    private static final String ENV_API          = "JAVI_PROFILING_API";  // 동일 컬렉터 재사용
    private static final String K8S_METRICS_PATH = "/api/collector/k8s-metrics";
    private static final long   DEFAULT_INTERVAL_SEC = 15L;

    // cgroup v1 경로
    private static final String CGROUPV1_CPU_USAGE   = "/sys/fs/cgroup/cpu/cpuacct.usage";    // nanoseconds
    private static final String CGROUPV1_CPU_QUOTA   = "/sys/fs/cgroup/cpu/cpu.cfs_quota_us"; // microseconds (-1 = unlimited)
    private static final String CGROUPV1_CPU_PERIOD  = "/sys/fs/cgroup/cpu/cpu.cfs_period_us";
    private static final String CGROUPV1_MEM_USAGE   = "/sys/fs/cgroup/memory/memory.usage_in_bytes";
    private static final String CGROUPV1_MEM_LIMIT   = "/sys/fs/cgroup/memory/memory.limit_in_bytes";
    private static final String CGROUPV1_MEM_STAT    = "/sys/fs/cgroup/memory/memory.stat";   // rss 포함

    // cgroup v2 경로
    private static final String CGROUPV2_CPU_STAT    = "/sys/fs/cgroup/cpu.stat";   // usage_usec
    private static final String CGROUPV2_CPU_MAX     = "/sys/fs/cgroup/cpu.max";    // "quota period" or "max period"
    private static final String CGROUPV2_MEM_CURRENT = "/sys/fs/cgroup/memory.current";
    private static final String CGROUPV2_MEM_MAX     = "/sys/fs/cgroup/memory.max"; // "max" = unlimited
    private static final String CGROUPV2_MEM_STAT    = "/sys/fs/cgroup/memory.stat"; // anon = RSS

    private static volatile ScheduledExecutorService executor;

    // CPU rate 계산을 위한 이전 값 저장
    private static final AtomicLong lastCpuNs   = new AtomicLong(-1);
    private static final AtomicLong lastSampleMs = new AtomicLong(-1);

    private static volatile HttpClient httpClient;
    private static volatile String collectorApiBase;
    private static volatile String serviceName;
    private static volatile String podName;
    private static volatile String nodeName;
    private static volatile String namespace;
    private static volatile String host;
    private static volatile String containerID;

    private K8sMetricsCollector() {}

    /** K8s 메트릭 수집을 시작한다. 즉시 1회 수집 후 interval 간격으로 반복한다. */
    public static void start() {
        if ("false".equalsIgnoreCase(System.getenv(ENV_ENABLED))) {
            AgentLogger.info("[k8s-metrics] JAVI_K8S_METRICS_ENABLED=false — 비활성화");
            return;
        }
        if (executor != null) return;

        Map<String, String> resource = ResourceInfo.getAttributes();
        serviceName  = resource.getOrDefault("service.name", AgentConfig.get().getServiceName());
        podName      = resource.getOrDefault("k8s.pod.name", "");
        nodeName     = resource.getOrDefault("k8s.node.name", "");
        namespace    = resource.getOrDefault("k8s.namespace.name", "");
        host         = resource.getOrDefault("host.name", "");
        containerID  = resource.getOrDefault("container.id", "");
        collectorApiBase = resolveApiBase();

        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "javi-k8s-metrics-http");
                    t.setDaemon(true);
                    return t;
                }))
                .build();

        long intervalSec = parseLong(ENV_INTERVAL_SEC, DEFAULT_INTERVAL_SEC);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-k8s-metrics");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(K8sMetricsCollector::collect, 0, intervalSec, TimeUnit.SECONDS);
        AgentLogger.info("[k8s-metrics] 시작 — 주기: " + intervalSec + "s, 대상: " + collectorApiBase);
    }

    public static void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    static void collect() {
        try {
            boolean v2 = isCgroupV2();
            double cpuUsageMillicore = v2 ? readCpuUsageV2() : readCpuUsageV1();
            double cpuLimitMillicore = v2 ? readCpuLimitV2() : readCpuLimitV1();
            long   memUsageBytes     = v2 ? readLongFile(CGROUPV2_MEM_CURRENT) : readLongFile(CGROUPV1_MEM_USAGE);
            long   memLimitBytes     = v2 ? readMemLimitV2()  : readMemLimitV1();
            long   memRssBytes       = v2 ? readStatField(CGROUPV2_MEM_STAT, "anon")
                                          : readStatField(CGROUPV1_MEM_STAT, "rss");

            String json = buildJson(cpuUsageMillicore, cpuLimitMillicore,
                                    memUsageBytes, memLimitBytes, memRssBytes);
            send(json);
        } catch (Throwable t) {
            AgentLogger.debug("[k8s-metrics] 수집 오류: " + t.getMessage());
        }
    }

    // ---- cgroup 버전 감지 ----

    private static boolean isCgroupV2() {
        return new java.io.File(CGROUPV2_CPU_STAT).exists();
    }

    // ---- CPU 사용량 (rate 계산) ----

    /**
     * cgroup v1: cpuacct.usage (누적 nanoseconds) → delta rate → millicores
     */
    private static double readCpuUsageV1() {
        long nowNs = readLongFile(CGROUPV1_CPU_USAGE);
        return computeCpuMillicore(nowNs, 1L);
    }

    /**
     * cgroup v2: cpu.stat의 usage_usec (microseconds) → convert ns → millicores
     */
    private static double readCpuUsageV2() {
        long usageUsec = readStatField(CGROUPV2_CPU_STAT, "usage_usec");
        long nowNs = usageUsec * 1000L; // usec → ns
        return computeCpuMillicore(nowNs, 1L);
    }

    /**
     * 이전 수집값과 경과 시간으로 CPU 사용량(millicores) 계산.
     * 최초 수집 시 이전 값이 없으므로 0을 반환한다.
     */
    private static double computeCpuMillicore(long cpuNsNow, long unusedMultiplier) {
        long nowMs   = System.currentTimeMillis();
        long prevNs  = lastCpuNs.getAndSet(cpuNsNow);
        long prevMs  = lastSampleMs.getAndSet(nowMs);

        if (prevNs < 0 || prevMs < 0) {
            return 0.0; // 최초 수집: 이전 값 없음
        }
        long deltaNs = cpuNsNow - prevNs;
        long deltaMs = nowMs - prevMs;
        if (deltaMs <= 0 || deltaNs < 0) return 0.0;

        // deltaNs / (deltaMs * 1_000_000 ns/ms) * 1000 millicores/core
        return (double) deltaNs / (deltaMs * 1_000_000L) * 1000.0;
    }

    // ---- CPU 한도 ----

    /** cgroup v1: quota / period * 1000 millicores. -1이면 unlimited → 0 반환 */
    private static double readCpuLimitV1() {
        long quota  = readLongFile(CGROUPV1_CPU_QUOTA);
        long period = readLongFile(CGROUPV1_CPU_PERIOD);
        if (quota <= 0 || period <= 0) return 0.0; // unlimited
        return (double) quota / period * 1000.0;
    }

    /** cgroup v2: cpu.max 형식 "quota period" or "max period" */
    private static double readCpuLimitV2() {
        String line = readFirstLine(CGROUPV2_CPU_MAX);
        if (line == null || line.startsWith("max")) return 0.0; // unlimited
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return 0.0;
        try {
            long quota  = Long.parseLong(parts[0]);
            long period = Long.parseLong(parts[1]);
            if (period <= 0) return 0.0;
            return (double) quota / period * 1000.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ---- 메모리 한도 ----

    /** cgroup v1: memory.limit_in_bytes — 매우 큰 값(>1TB)이면 unlimited */
    private static long readMemLimitV1() {
        long limit = readLongFile(CGROUPV1_MEM_LIMIT);
        return (limit > 0 && limit < Long.MAX_VALUE / 2) ? limit : 0L;
    }

    /** cgroup v2: memory.max — "max"이면 unlimited */
    private static long readMemLimitV2() {
        String line = readFirstLine(CGROUPV2_MEM_MAX);
        if (line == null || line.trim().equals("max")) return 0L;
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ---- 공통 파일 읽기 ----

    private static long readLongFile(String path) {
        String line = readFirstLine(path);
        if (line == null) return 0L;
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * "key value" 형태의 stat 파일에서 특정 키 값을 읽는다.
     * memory.stat, cpu.stat 모두 동일 포맷.
     */
    private static long readStatField(String path, String fieldName) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+", 2);
                if (parts.length == 2 && fieldName.equals(parts[0])) {
                    return Long.parseLong(parts[1].trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {}
        return 0L;
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // ---- JSON 직렬화 & HTTP 전송 ----

    private static String buildJson(double cpuUsage, double cpuLimit,
                                    long memUsage, long memLimit, long memRss) {
        return "{"
                + "\"service_name\":\"" + jsonEscape(serviceName) + "\","
                + "\"pod_name\":\"" + jsonEscape(podName) + "\","
                + "\"node_name\":\"" + jsonEscape(nodeName) + "\","
                + "\"namespace\":\"" + jsonEscape(namespace) + "\","
                + "\"host\":\"" + jsonEscape(host) + "\","
                + "\"container_id\":\"" + jsonEscape(containerID) + "\","
                + "\"cpu_usage_millicore\":" + String.format("%.3f", cpuUsage) + ","
                + "\"cpu_limit_millicore\":" + String.format("%.3f", cpuLimit) + ","
                + "\"memory_usage_bytes\":" + memUsage + ","
                + "\"memory_limit_bytes\":" + memLimit + ","
                + "\"memory_rss_bytes\":" + memRss
                + "}";
    }

    private static void send(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(collectorApiBase + K8S_METRICS_PATH))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (Exception e) {
            AgentLogger.debug("[k8s-metrics] 요청 생성 실패: " + e.getMessage());
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        AgentLogger.debug("[k8s-metrics] 전송 실패: " + ex.getMessage());
                    } else if (resp.statusCode() >= 400) {
                        AgentLogger.debug("[k8s-metrics] 컬렉터 오류 HTTP " + resp.statusCode());
                    }
                });
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String resolveApiBase() {
        String val = System.getenv(ENV_API);
        if (val != null && !val.isEmpty()) return val.replaceAll("/+$", "");
        String otlpEndpoint = AgentConfig.get().getExporterEndpoint();
        try {
            URI uri = URI.create(otlpEndpoint);
            return uri.getScheme() + "://" + uri.getHost() + ":8080";
        } catch (Exception e) {
            return "http://localhost:8080";
        }
    }

    private static long parseLong(String envKey, long defaultVal) {
        try {
            String v = System.getenv(envKey);
            return (v != null && !v.isEmpty()) ? Long.parseLong(v) : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
