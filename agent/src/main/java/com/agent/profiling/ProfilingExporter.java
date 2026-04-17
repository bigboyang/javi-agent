package com.agent.profiling;

import com.agent.common.ResourceInfo;
import com.agent.config.AgentConfig;
import com.agent.logs.AgentLogger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
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
 * <p>외부 네이티브 라이브러리(async-profiler) 없이 순수 Java로 CPU 프로파일링을 구현한다.
 * 주기적인 스레드 스택 샘플링으로 collapsed flame graph 포맷을 생성한 뒤
 * {@code POST /api/collector/profiling} 으로 전송한다.
 *
 * <p>샘플링 알고리즘:
 * <ol>
 *   <li>{@code sampleDurationMs} 동안 {@code sampleIntervalMs} 간격으로 모든 스레드 스택을 덤프</li>
 *   <li>스택 트레이스를 세미콜론(;)으로 결합하여 collapsed 포맷 생성</li>
 *   <li>동일 스택의 등장 횟수를 카운트하여 "stack count" 형태로 집계</li>
 *   <li>Flame Graph 렌더링 도구(Brendan Gregg의 flamegraph.pl 등)와 호환</li>
 * </ol>
 *
 * <p>환경변수:
 * <ul>
 *   <li>{@code JAVI_PROFILING_API} — 컬렉터 API 기본 URL (기본: http://localhost:8080)</li>
 *   <li>{@code JAVI_PROFILING_ENABLED} — "false"이면 비활성화 (기본: true)</li>
 *   <li>{@code JAVI_PROFILING_SAMPLE_DURATION_MS} — 1회 수집 시간 ms (기본: 10000)</li>
 *   <li>{@code JAVI_PROFILING_SAMPLE_INTERVAL_MS} — 스택 덤프 간격 ms (기본: 10)</li>
 * </ul>
 */
public final class ProfilingExporter {

    private static final String ENV_API          = "JAVI_PROFILING_API";
    private static final String ENV_ENABLED      = "JAVI_PROFILING_ENABLED";
    private static final String ENV_DURATION_MS  = "JAVI_PROFILING_SAMPLE_DURATION_MS";
    private static final String ENV_INTERVAL_MS  = "JAVI_PROFILING_SAMPLE_INTERVAL_MS";

    private static final String PROFILING_PATH   = "/api/collector/profiling";
    private static final long   DEFAULT_DURATION_MS  = 10_000L;  // 10초 수집
    private static final long   DEFAULT_INTERVAL_MS  = 10L;      // 10ms 샘플 간격

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

    private final ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();

    public ProfilingExporter() {
        Map<String, String> resource = ResourceInfo.getAttributes();

        this.collectorApiBase = resolveApiBase();
        this.sampleDurationMs = parseLong(ENV_DURATION_MS, DEFAULT_DURATION_MS);
        this.sampleIntervalMs = parseLong(ENV_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        this.serviceName      = resource.getOrDefault("service.name",
                AgentConfig.get().getServiceName());
        this.host             = resource.getOrDefault("host.name", "");
        this.k8sPod           = resource.getOrDefault("k8s.pod.name", "");
        this.k8sNode          = resource.getOrDefault("k8s.node.name", "");
        this.k8sNamespace     = resource.getOrDefault("k8s.namespace.name", "");

        String enabledVal = System.getenv(ENV_ENABLED);
        this.enabled = new AtomicBoolean(!"false".equalsIgnoreCase(enabledVal));

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
     *
     * <p>{@code sampleDurationMs} 동안 {@code sampleIntervalMs} 간격으로
     * 모든 스레드 스택을 샘플링하여 collapsed 포맷으로 변환 후 전송한다.
     */
    public void collectAndExport() {
        if (!enabled.get()) return;

        long startMs = System.currentTimeMillis();
        Map<String, Integer> stackCounts = sampleStacks();
        long endMs = System.currentTimeMillis();

        if (stackCounts.isEmpty()) {
            AgentLogger.debug("[profiling] 수집된 스택이 없음 — 전송 생략");
            return;
        }

        String payload = buildCollapsedPayload(stackCounts);
        String id = UUID.randomUUID().toString();

        sendSnapshot(id, payload, "cpu", "collapsed", endMs - startMs);
    }

    /**
     * sampleDurationMs 동안 모든 스레드를 주기적으로 샘플링한다.
     *
     * @return 스택문자열 → 카운트 맵
     */
    private Map<String, Integer> sampleStacks() {
        Map<String, Integer> stackCounts = new HashMap<>(512);
        long deadline = System.currentTimeMillis() + sampleDurationMs;

        while (System.currentTimeMillis() < deadline) {
            // 현재 스레드(프로파일링 스레드)를 제외한 모든 활성 스레드의 스택 수집
            ThreadInfo[] infos = threadMX.dumpAllThreads(false, false);
            for (ThreadInfo ti : infos) {
                // 에이전트 내부 스레드, JVM 시스템 스레드는 제외
                String threadName = ti.getThreadName();
                if (isAgentThread(threadName)) continue;

                Thread.State state = ti.getThreadState();
                // RUNNABLE 상태의 스레드만 CPU 사용으로 간주
                if (state != Thread.State.RUNNABLE) continue;

                StackTraceElement[] frames = ti.getStackTrace();
                if (frames == null || frames.length == 0) continue;

                String collapsed = buildCollapsedStack(frames);
                stackCounts.merge(collapsed, 1, Integer::sum);
            }

            try {
                Thread.sleep(sampleIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return stackCounts;
    }

    /**
     * 스택 프레임 배열을 collapsed 포맷으로 변환.
     * Flame Graph 방향: 가장 오래된 프레임(root)이 왼쪽.
     *
     * <p>예: {@code com.example.App.main;com.example.Service.handle;com.example.Dao.query}
     */
    private String buildCollapsedStack(StackTraceElement[] frames) {
        // frames[0] = 가장 최근 호출 (leaf), frames[n-1] = root
        // Flame Graph: root → leaf 순서로 연결
        StringBuilder sb = new StringBuilder(frames.length * 64);
        for (int i = frames.length - 1; i >= 0; i--) {
            StackTraceElement frame = frames[i];
            if (i < frames.length - 1) sb.append(';');
            sb.append(frame.getClassName())
              .append('.')
              .append(frame.getMethodName());
        }
        return sb.toString();
    }

    /**
     * 스택카운트 맵을 collapsed flame graph 텍스트 포맷으로 직렬화.
     *
     * <p>각 라인: {@code stack;frames count}
     */
    private String buildCollapsedPayload(Map<String, Integer> stackCounts) {
        StringBuilder sb = new StringBuilder(stackCounts.size() * 100);
        for (Map.Entry<String, Integer> e : stackCounts.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 프로파일링 스냅샷을 컬렉터에 전송한다.
     */
    private void sendSnapshot(String id, String payload, String profileType,
                               String format, long durationMs) {
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
                                + " stacks=" + payload.lines().count()
                                + " duration_ms=" + durationMs);
                    }
                });
    }

    /**
     * 전송용 JSON 직렬화. 외부 JSON 라이브러리 의존성 없이 직접 생성.
     */
    private String buildJson(String id, String payload, String profileType,
                              String format, long durationMs) {
        // payload는 Base64 인코딩 없이 collapsed 텍스트 전송 (컬렉터가 String으로 저장)
        String escapedPayload = payload
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        return "{"
                + "\"id\":\"" + id + "\","
                + "\"service_name\":\"" + jsonEscape(serviceName) + "\","
                + "\"profile_type\":\"" + profileType + "\","
                + "\"format\":\"" + format + "\","
                + "\"payload\":\"" + escapedPayload + "\","
                + "\"host\":\"" + jsonEscape(host) + "\","
                + "\"k8s_pod\":\"" + jsonEscape(k8sPod) + "\","
                + "\"k8s_node\":\"" + jsonEscape(k8sNode) + "\","
                + "\"k8s_namespace\":\"" + jsonEscape(k8sNamespace) + "\","
                + "\"duration_ms\":" + durationMs
                + "}";
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 에이전트/JVM 내부 스레드 여부 판별.
     * 이 스레드들은 CPU 프로파일링 대상에서 제외한다.
     */
    private boolean isAgentThread(String name) {
        if (name == null) return false;
        return name.startsWith("javi-")
                || name.startsWith("GC ")
                || name.startsWith("G1 ")
                || name.startsWith("Finalizer")
                || name.startsWith("Reference Handler")
                || name.startsWith("Signal Dispatcher")
                || name.startsWith("Attach Listener")
                || name.startsWith("VM Thread")
                || name.startsWith("VM Periodic")
                || name.startsWith("C1 ")
                || name.startsWith("C2 ")
                || name.startsWith("Common-Cleaner");
    }

    private static String resolveApiBase() {
        String val = System.getenv(ENV_API);
        if (val != null && !val.isEmpty()) return val.replaceAll("/+$", "");
        // JAVI_COLLECTOR_ENDPOINT가 설정되어 있으면 포트를 8080으로 교체하여 API URL로 사용
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
