package com.agent.profiling;

import com.agent.common.ResourceInfo;
import com.agent.config.AgentConfig;
import com.agent.logs.AgentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuous Profiling Exporter (GAP-07).
 *
 * <p>CPU 프로파일링 스냅샷을 수집하여 {@code POST /api/collector/profiling}으로 전송한다.
 *
 * <p>백엔드 우선순위 (자동 감지):
 * <ol>
 *   <li>{@link AsyncProfilerBackend} — async-profiler JAR이 클래스패스에 있거나
 *       {@code -agentpath:libasyncProfiler.so}로 로드된 경우. Linux perf_events 기반,
 *       safepoint 편향 없음, 네이티브 프레임 포함.</li>
 *   <li>{@link JfrProfilerBackend} — JDK 11+ Flight Recorder 사용. 낮은 오버헤드,
 *       JVM 내부 프레임 가시화.</li>
 *   <li>{@link ThreadSamplingBackend} — ThreadMXBean 기반 폴백. 외부 의존성 없음.</li>
 * </ol>
 *
 * <p>환경변수:
 * <ul>
 *   <li>{@code JAVI_PROFILING_API} — 컬렉터 API 기본 URL (기본: http://localhost:8080)</li>
 *   <li>{@code JAVI_PROFILING_ENABLED} — "false"이면 비활성화 (기본: true)</li>
 *   <li>{@code JAVI_PROFILING_SAMPLE_DURATION_MS} — 1회 수집 시간 ms (기본: 10000)</li>
 *   <li>{@code JAVI_PROFILING_SAMPLE_INTERVAL_MS} — 샘플 간격 ms (기본: 10)</li>
 * </ul>
 */
public final class ProfilingExporter {

    private static final String ENV_API         = "JAVI_PROFILING_API";
    private static final String ENV_ENABLED     = "JAVI_PROFILING_ENABLED";
    private static final String ENV_DURATION_MS = "JAVI_PROFILING_SAMPLE_DURATION_MS";
    private static final String ENV_INTERVAL_MS = "JAVI_PROFILING_SAMPLE_INTERVAL_MS";

    private static final String PROFILING_PATH      = "/api/collector/profiling";
    private static final long   DEFAULT_DURATION_MS  = 10_000L;
    private static final long   DEFAULT_INTERVAL_MS  = 10L;

    private final String        collectorApiBase;
    private final long          sampleDurationMs;
    private final long          sampleIntervalMs;
    private final String        serviceName;
    private final String        host;
    private final String        k8sPod;
    private final String        k8sNode;
    private final String        k8sNamespace;
    private final HttpClient    httpClient;
    private final AtomicBoolean enabled;
    private final ProfilerBackend backend;

    public ProfilingExporter() {
        Map<String, String> resource = ResourceInfo.getAttributes();

        this.collectorApiBase = resolveApiBase();
        this.sampleDurationMs = parseLong(ENV_DURATION_MS, DEFAULT_DURATION_MS);
        this.sampleIntervalMs = parseLong(ENV_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        this.serviceName      = jsonEscape(resource.getOrDefault("service.name",
                AgentConfig.get().getServiceName()));
        this.host             = jsonEscape(resource.getOrDefault("host.name", ""));
        this.k8sPod           = jsonEscape(resource.getOrDefault("k8s.pod.name", ""));
        this.k8sNode          = jsonEscape(resource.getOrDefault("k8s.node.name", ""));
        this.k8sNamespace     = jsonEscape(resource.getOrDefault("k8s.namespace.name", ""));

        String enabledVal = System.getenv(ENV_ENABLED);
        this.enabled = new AtomicBoolean(!"false".equalsIgnoreCase(enabledVal));

        this.backend = selectBackend();

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "javi-profiling-http");
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    /**
     * CPU 프로파일링 1회 수집 및 전송.
     */
    public void collectAndExport() {
        if (!enabled.get()) return;

        long startMs = System.currentTimeMillis();
        Map<String, Integer> stackCounts = backend.collectCpuStacks(sampleDurationMs, sampleIntervalMs);
        long endMs = System.currentTimeMillis();

        if (stackCounts.isEmpty()) {
            AgentLogger.debug("[profiling] 수집된 스택이 없음 — 전송 생략");
            return;
        }

        long durationMs = endMs - startMs;

        Map<String, Integer> cpuStacks   = new HashMap<>();
        Map<String, Integer> allocStacks = new HashMap<>();
        for (Map.Entry<String, Integer> e : stackCounts.entrySet()) {
            if (e.getKey().startsWith(JfrProfilerBackend.ALLOC_PREFIX)) {
                allocStacks.put(e.getKey().substring(JfrProfilerBackend.ALLOC_PREFIX.length()), e.getValue());
            } else {
                cpuStacks.put(e.getKey(), e.getValue());
            }
        }

        if (!cpuStacks.isEmpty()) {
            sendSnapshot(UUID.randomUUID().toString(), buildCollapsedPayload(cpuStacks),
                    "cpu", "collapsed", durationMs, cpuStacks.size());
        }
        if (!allocStacks.isEmpty()) {
            sendSnapshot(UUID.randomUUID().toString(), buildCollapsedPayload(allocStacks),
                    "alloc", "collapsed", durationMs, allocStacks.size());
        }
    }

    private String buildCollapsedPayload(Map<String, Integer> stackCounts) {
        StringBuilder sb = new StringBuilder(stackCounts.size() * 100);
        for (Map.Entry<String, Integer> e : stackCounts.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private void sendSnapshot(String id, String payload, String profileType,
                               String format, long durationMs, int stackCount) {
        String json = buildJson(id, payload, profileType, format, durationMs);
        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(collectorApiBase + PROFILING_PATH))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (Exception e) {
            AgentLogger.warn("[profiling] 요청 생성 실패: " + e.getMessage());
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        AgentLogger.warn("[profiling] 전송 실패: " + ex.getMessage());
                    } else if (resp.statusCode() >= 400) {
                        AgentLogger.warn("[profiling] 컬렉터 오류 HTTP " + resp.statusCode());
                    } else {
                        AgentLogger.debug("[profiling] 전송 완료 id=" + id
                                + " backend=" + backend.name()
                                + " stacks=" + stackCount
                                + " duration_ms=" + durationMs);
                    }
                });
    }

    private String buildJson(String id, String payload, String profileType,
                              String format, long durationMs) {
        String escapedPayload = payload
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        return "{"
                + "\"id\":\"" + id + "\","
                + "\"service_name\":\"" + serviceName + "\","
                + "\"profile_type\":\"" + profileType + "\","
                + "\"format\":\"" + format + "\","
                + "\"backend\":\"" + backend.name() + "\","
                + "\"payload\":\"" + escapedPayload + "\","
                + "\"host\":\"" + host + "\","
                + "\"k8s_pod\":\"" + k8sPod + "\","
                + "\"k8s_node\":\"" + k8sNode + "\","
                + "\"k8s_namespace\":\"" + k8sNamespace + "\","
                + "\"duration_ms\":" + durationMs
                + "}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 사용 가능한 최적의 프로파일러 백엔드를 선택한다.
     * async-profiler → JFR → ThreadMXBean 순으로 시도한다.
     */
    private static ProfilerBackend selectBackend() {
        AsyncProfilerBackend ap = AsyncProfilerBackend.tryCreate();
        if (ap != null) return ap;

        if (JfrProfilerBackend.isAvailable()) {
            AgentLogger.info("[profiling] JFR 백엔드 사용 (낮은 오버헤드, JDK Flight Recorder)");
            return new JfrProfilerBackend();
        }

        AgentLogger.info("[profiling] ThreadMXBean 샘플링 백엔드 사용 (폴백)");
        return new ThreadSamplingBackend();
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

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }
}
