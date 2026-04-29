package com.agent.metric;

import com.agent.common.ResourceInfo;
import com.agent.logs.AgentLogger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pod 내부 cgroup 파일을 읽어 K8s 리소스 메트릭을 수집하고
 * OTLP 파이프라인(MetricRegistry)으로 전송한다.
 *
 * <p>수집 항목 (OTel Semantic Conventions):
 * <ul>
 *   <li>k8s.container.cpu.usage_millicores  — CPU 사용량 rate (millicores)</li>
 *   <li>k8s.container.cpu.limit_millicores  — CPU 한도 (0 = unlimited)</li>
 *   <li>container.memory.usage              — 메모리 사용량 (bytes)</li>
 *   <li>container.memory.limit              — 메모리 한도 (bytes, 0 = unlimited)</li>
 *   <li>container.memory.rss               — RSS (페이지 캐시 제외)</li>
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
 *   <li>수집 주기는 {@link MetricsCollectorScheduler} {@code JAVI_METRICS_INTERVAL_SEC}으로 제어</li>
 * </ul>
 */
public final class K8sMetricsCollector {

    private static final String ENV_ENABLED = "JAVI_K8S_METRICS_ENABLED";

    // cgroup v1 경로
    private static final String CGROUPV1_CPU_USAGE   = "/sys/fs/cgroup/cpu/cpuacct.usage";
    private static final String CGROUPV1_CPU_QUOTA   = "/sys/fs/cgroup/cpu/cpu.cfs_quota_us";
    private static final String CGROUPV1_CPU_PERIOD  = "/sys/fs/cgroup/cpu/cpu.cfs_period_us";
    private static final String CGROUPV1_MEM_USAGE   = "/sys/fs/cgroup/memory/memory.usage_in_bytes";
    private static final String CGROUPV1_MEM_LIMIT   = "/sys/fs/cgroup/memory/memory.limit_in_bytes";
    private static final String CGROUPV1_MEM_STAT    = "/sys/fs/cgroup/memory/memory.stat";

    // cgroup v2 경로
    private static final String CGROUPV2_CPU_STAT    = "/sys/fs/cgroup/cpu.stat";
    private static final String CGROUPV2_CPU_MAX     = "/sys/fs/cgroup/cpu.max";
    private static final String CGROUPV2_MEM_CURRENT = "/sys/fs/cgroup/memory.current";
    private static final String CGROUPV2_MEM_MAX     = "/sys/fs/cgroup/memory.max";
    private static final String CGROUPV2_MEM_STAT    = "/sys/fs/cgroup/memory.stat";

    // CPU rate 계산을 위한 이전 값 저장
    private static final AtomicLong lastCpuNs    = new AtomicLong(-1);
    private static final AtomicLong lastSampleMs = new AtomicLong(-1);

    // K8s 리소스 태그 — start() 시 1회 초기화
    private static volatile Map<String, String> k8sTags = Collections.emptyMap();
    private static volatile boolean enabled = false;

    private K8sMetricsCollector() {}

    /**
     * k8sTags를 초기화한다. 스케줄링은 {@link MetricsCollectorScheduler}가 담당한다.
     */
    public static synchronized void start() {
        if ("false".equalsIgnoreCase(System.getenv(ENV_ENABLED))) {
            AgentLogger.info("[k8s-metrics] JAVI_K8S_METRICS_ENABLED=false — 비활성화");
            return;
        }
        if (enabled) return;

        Map<String, String> resource = ResourceInfo.getAttributes();
        Map<String, String> tags = new HashMap<>(4);
        putIfPresent(tags, resource, "k8s.namespace.name");
        putIfPresent(tags, resource, "k8s.pod.name");
        putIfPresent(tags, resource, "k8s.node.name");
        putIfPresent(tags, resource, "k8s.container.name");
        k8sTags = Collections.unmodifiableMap(tags);
        enabled = true;
        AgentLogger.info("[k8s-metrics] 초기화 완료 (스케줄링은 MetricsCollectorScheduler)");
    }

    public static void stop() {}

    static void collect() {
        if (!enabled) return;
        try {
            String metricsScope = com.agent.config.RemoteConfigHolder.get().getMetrics();
            if ("disabled".equals(metricsScope)) return;

            boolean v2 = isCgroupV2();
            double cpuUsageMillicore = v2 ? readCpuUsageV2() : readCpuUsageV1();
            double cpuLimitMillicore = v2 ? readCpuLimitV2() : readCpuLimitV1();
            long   memUsageBytes     = v2 ? readLongFile(CGROUPV2_MEM_CURRENT) : readLongFile(CGROUPV1_MEM_USAGE);
            long   memLimitBytes     = v2 ? readMemLimitV2()  : readMemLimitV1();
            long   memRssBytes       = v2 ? readStatField(CGROUPV2_MEM_STAT, "anon")
                                          : readStatField(CGROUPV1_MEM_STAT, "rss");

            MetricRegistry reg = MetricRegistry.get();

            if (cpuUsageMillicore > 0) {
                reg.gauge("k8s.container.cpu.usage_millicores", "Container CPU usage", "m{cpu}", k8sTags)
                        .set((long) cpuUsageMillicore);
            }
            if (cpuLimitMillicore > 0) {
                reg.gauge("k8s.container.cpu.limit_millicores", "Container CPU limit", "m{cpu}", k8sTags)
                        .set((long) cpuLimitMillicore);
            }
            if (memUsageBytes > 0) {
                reg.gauge("container.memory.usage", "Container memory usage", "By", k8sTags)
                        .set(memUsageBytes);
            }
            if (memLimitBytes > 0) {
                reg.gauge("container.memory.limit", "Container memory limit", "By", k8sTags)
                        .set(memLimitBytes);
            }
            if (memRssBytes > 0) {
                reg.gauge("container.memory.rss", "Container memory RSS", "By", k8sTags)
                        .set(memRssBytes);
            }
        } catch (Throwable t) {
            AgentLogger.debug("[k8s-metrics] 수집 오류: " + t.getMessage());
        }
    }

    // ---- cgroup 버전 감지 ----

    private static boolean isCgroupV2() {
        return new java.io.File(CGROUPV2_CPU_STAT).exists();
    }

    // ---- CPU 사용량 (rate 계산) ----

    private static double readCpuUsageV1() {
        long nowNs = readLongFile(CGROUPV1_CPU_USAGE);
        return computeCpuMillicore(nowNs);
    }

    private static double readCpuUsageV2() {
        long usageUsec = readStatField(CGROUPV2_CPU_STAT, "usage_usec");
        return computeCpuMillicore(usageUsec * 1000L);
    }

    private static double computeCpuMillicore(long cpuNsNow) {
        long nowMs  = System.currentTimeMillis();
        long prevNs = lastCpuNs.getAndSet(cpuNsNow);
        long prevMs = lastSampleMs.getAndSet(nowMs);

        if (prevNs < 0 || prevMs < 0) return 0.0;
        long deltaNs = cpuNsNow - prevNs;
        long deltaMs = nowMs - prevMs;
        if (deltaMs <= 0 || deltaNs < 0) return 0.0;
        return (double) deltaNs / (deltaMs * 1_000_000L) * 1000.0;
    }

    // ---- CPU 한도 ----

    private static double readCpuLimitV1() {
        long quota  = readLongFile(CGROUPV1_CPU_QUOTA);
        long period = readLongFile(CGROUPV1_CPU_PERIOD);
        if (quota <= 0 || period <= 0) return 0.0;
        return (double) quota / period * 1000.0;
    }

    private static double readCpuLimitV2() {
        String line = readFirstLine(CGROUPV2_CPU_MAX);
        if (line == null || line.startsWith("max")) return 0.0;
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

    private static long readMemLimitV1() {
        long limit = readLongFile(CGROUPV1_MEM_LIMIT);
        return (limit > 0 && limit < Long.MAX_VALUE / 2) ? limit : 0L;
    }

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

    // ---- 유틸 ----

    private static void putIfPresent(Map<String, String> dest, Map<String, String> src, String key) {
        String v = src.get(key);
        if (v != null && !v.isEmpty()) dest.put(key, v);
    }
}
