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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuous Profiling Exporter (GAP-07).
 *
 * <p>CPU 프로파일링 스냅샷을 수집하여 전송한다.
 *
 * <p>포맷 선택 ({@code JAVI_PROFILING_FORMAT}):
 * <ul>
 *   <li>{@code collapsed} (기본) — JSON payload, {@code POST /api/collector/profiling}</li>
 *   <li>{@code pprof}     — gzip-compressed pprof protobuf, {@code JAVI_PROFILING_PPROF_ENDPOINT}</li>
 * </ul>
 *
 * <p>재시도: 5xx/네트워크 오류 시 최대 {@value #MAX_ATTEMPTS}회, 지수 백오프.
 * 4xx는 재시도 없이 즉시 실패.
 *
 * <p>환경변수:
 * <ul>
 *   <li>{@code JAVI_PROFILING_API}                — 컬렉터 API 기본 URL (기본: http://localhost:8080)</li>
 *   <li>{@code JAVI_PROFILING_ENABLED}            — "false"이면 비활성화</li>
 *   <li>{@code JAVI_PROFILING_SAMPLE_DURATION_MS} — 1회 수집 시간 ms (기본: 10000)</li>
 *   <li>{@code JAVI_PROFILING_SAMPLE_INTERVAL_MS} — 샘플 간격 ms (기본: 10)</li>
 *   <li>{@code JAVI_PROFILING_FORMAT}             — "collapsed" | "pprof" (기본: collapsed)</li>
 *   <li>{@code JAVI_PROFILING_PPROF_ENDPOINT}     — pprof 전송 엔드포인트 URL</li>
 * </ul>
 */
public final class ProfilingExporter {

    private static final String ENV_API         = "JAVI_PROFILING_API";
    private static final String ENV_ENABLED     = "JAVI_PROFILING_ENABLED";
    private static final String ENV_DURATION_MS = "JAVI_PROFILING_SAMPLE_DURATION_MS";
    private static final String ENV_INTERVAL_MS = "JAVI_PROFILING_SAMPLE_INTERVAL_MS";
    private static final String ENV_FORMAT      = "JAVI_PROFILING_FORMAT";
    private static final String ENV_PPROF_EP    = "JAVI_PROFILING_PPROF_ENDPOINT";

    private static final String PROFILING_PATH       = "/api/collector/profiling";
    private static final long   DEFAULT_DURATION_MS  = 10_000L;
    private static final long   DEFAULT_INTERVAL_MS  = 10L;

    private static final int    MAX_ATTEMPTS        = 3;
    private static final long[] RETRY_BACKOFF_MS    = {0L, 1_000L, 2_000L};

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
    private final boolean       pprofEnabled;
    private final String        pprofEndpoint;

    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "javi-profiling-retry");
        t.setDaemon(true);
        return t;
    });

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

        String fmt = System.getenv(ENV_FORMAT);
        this.pprofEnabled = "pprof".equalsIgnoreCase(fmt);
        this.pprofEndpoint = resolvePprofEndpoint();

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
            String id = UUID.randomUUID().toString();
            if (pprofEnabled) {
                byte[] pprofBytes = PprofEncoder.encode(cpuStacks, "cpu", durationMs, sampleIntervalMs);
                sendPprof(id, pprofBytes);
            } else {
                sendSnapshot(id, buildCollapsedPayload(cpuStacks), "cpu", "collapsed",
                        durationMs, cpuStacks.size());
            }
        }
        if (!allocStacks.isEmpty()) {
            String id = UUID.randomUUID().toString();
            if (pprofEnabled) {
                byte[] pprofBytes = PprofEncoder.encode(allocStacks, "alloc", durationMs, sampleIntervalMs);
                sendPprof(id, pprofBytes);
            } else {
                sendSnapshot(id, buildCollapsedPayload(allocStacks), "alloc", "collapsed",
                        durationMs, allocStacks.size());
            }
        }
    }

    private String buildCollapsedPayload(Map<String, Integer> stackCounts) {
        StringBuilder sb = new StringBuilder(stackCounts.size() * 100);
        for (Map.Entry<String, Integer> e : stackCounts.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    // ---- collapsed JSON 전송 ----

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
        doSend(request, id, 0);
    }

    // ---- pprof binary 전송 ----

    private void sendPprof(String id, byte[] pprofGzipBytes) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(pprofEndpoint))
                    .timeout(Duration.ofSeconds(15))
                    // pprof 파일은 gzip-compressed protobuf (파일 포맷 수준, Content-Encoding 헤더 없음)
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(pprofGzipBytes))
                    .build();
        } catch (Exception e) {
            AgentLogger.warn("[profiling] pprof 요청 생성 실패: " + e.getMessage());
            return;
        }
        doSend(request, id, 0);
    }

    // ---- 재시도 로직 ----

    /**
     * HTTP 전송 + 재시도. 5xx/네트워크 오류만 재시도, 4xx는 즉시 포기.
     * 재시도 스케줄링은 retryScheduler 를 사용해 httpClient 스레드를 점유하지 않는다.
     */
    private void doSend(HttpRequest request, String id, int attempt) {
        retryScheduler.schedule(() -> {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, ex) -> {
                        if (ex != null) {
                            scheduleRetry(request, id, attempt, "네트워크 오류: " + ex.getMessage());
                        } else if (resp.statusCode() >= 500) {
                            scheduleRetry(request, id, attempt, "HTTP " + resp.statusCode());
                        } else if (resp.statusCode() >= 400) {
                            AgentLogger.warn("[profiling] 컬렉터 오류 HTTP " + resp.statusCode()
                                    + " id=" + id + " (재시도 없음)");
                        } else {
                            AgentLogger.debug("[profiling] 전송 완료 id=" + id
                                    + " backend=" + backend.name()
                                    + " attempt=" + attempt);
                        }
                    });
        }, RETRY_BACKOFF_MS[attempt], TimeUnit.MILLISECONDS);
    }

    private void scheduleRetry(HttpRequest request, String id, int attempt, String reason) {
        int next = attempt + 1;
        if (next < MAX_ATTEMPTS) {
            AgentLogger.debug("[profiling] 재시도 " + next + "/" + (MAX_ATTEMPTS - 1)
                    + " id=" + id + " cause=" + reason);
            doSend(request, id, next);
        } else {
            AgentLogger.warn("[profiling] 전송 최종 실패 id=" + id + " cause=" + reason);
        }
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
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return "http://localhost:4318";
        }
    }

    private static String resolvePprofEndpoint() {
        String val = System.getenv(ENV_PPROF_EP);
        if (val != null && !val.isEmpty()) return val;
        return resolveApiBase() + "/api/collector/profiling/pprof";
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
