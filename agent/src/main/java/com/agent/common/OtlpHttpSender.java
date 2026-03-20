package com.agent.common;

import com.agent.logs.AgentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP/HTTP JSON 전송을 담당하는 공통 유틸리티.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>Java 11 HttpClient 사용 — 외부 라이브러리 없음</li>
 *   <li>비동기 전송({@link #sendAsync}): Worker 스레드를 블로킹하지 않음. 재시도는
 *       {@link ScheduledExecutorService}를 통해 backoff 후 예약된다.</li>
 *   <li>동기 전송({@link #send}): shutdown 경로 등 블로킹이 허용되는 경우에만 사용.</li>
 *   <li>지수 백오프 재시도: 5xx / 네트워크 오류에만 적용, 4xx는 즉시 실패</li>
 *   <li>Retry-After 헤더 지원: 503 응답의 Retry-After를 파싱해 backoff에 반영</li>
 *   <li>Circuit Breaker: Half-Open 실패 시 lastOpenTime을 현재 시각으로 갱신하여
 *       30초 대기를 재시작한다 (기존 compareAndSet(0,now) 버그 수정)</li>
 *   <li>타임아웃: 연결 5초 고정, 요청 타임아웃은 설정 주입</li>
 *   <li>shutdown 이후 send()/sendAsync() 호출은 즉시 SHUTDOWN 반환</li>
 * </ul>
 *
 * <p>설정 환경변수:
 * <ul>
 *   <li>JAVI_COLLECTOR_ENDPOINT / javi.collector.endpoint (기본: http://localhost:4318)</li>
 *   <li>JAVI_COLLECTOR_TIMEOUT_MS / javi.collector.timeout.ms (기본: 10000)</li>
 * </ul>
 */
public final class OtlpHttpSender {

    // ---- 설정 키 ----
    private static final String ENV_ENDPOINT    = "JAVI_COLLECTOR_ENDPOINT";
    private static final String PROP_ENDPOINT   = "javi.collector.endpoint";
    private static final String ENV_TIMEOUT_MS  = "JAVI_COLLECTOR_TIMEOUT_MS";
    private static final String PROP_TIMEOUT_MS = "javi.collector.timeout.ms";

    private static final String DEFAULT_BASE_ENDPOINT = "http://localhost:4318";
    private static final long   DEFAULT_TIMEOUT_MS     = 10_000L;

    /** 재시도 간격 (ms): attempt 0=즉시, 1=1s, 2=5s, 3=10s */
    private static final long[] RETRY_BACKOFF_MS = {0L, 1_000L, 5_000L, 10_000L};

    /** 최대 재시도 횟수 (첫 시도 포함) */
    private static final int MAX_ATTEMPTS = 4;

    public enum SendResult { SUCCESS, FAILURE, SHUTDOWN, CIRCUIT_OPEN }

    // ---- Circuit Breaker 상태 ----
    private static final int  FAILURE_THRESHOLD = 5;
    private static final long OPEN_DURATION_MS  = 30_000L;

    private final AtomicBoolean isShutdown          = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * Circuit Breaker 상태:
     * <ul>
     *   <li>0       → CLOSED (정상)</li>
     *   <li>&gt; 0  → OPEN 또는 Half-Open (값 = 오픈된 시각 ms)</li>
     * </ul>
     *
     * <p>수정 포인트: Half-Open 실패 시 {@code compareAndSet(prev, now)}로 갱신해야 한다.
     * 기존 {@code compareAndSet(0, now)}는 이미 오픈된 상태(lastOpenTime != 0)에서
     * CAS가 항상 실패하여 circuit이 재-오픈되지 않는 버그를 가지고 있었다.
     */
    private final AtomicLong lastOpenTime = new AtomicLong(0);

    /**
     * 비동기 재시도 backoff 스케줄러.
     * 단일 스레드 — 재시도 예약만 담당하므로 충분하다.
     */
    private final ScheduledExecutorService retryScheduler;

    private final long                timeoutMs;
    private final Map<String, String> defaultHeaders;
    private final HttpClient          httpClient;

    private OtlpHttpSender(long timeoutMs, Map<String, String> headers) {
        this.timeoutMs      = timeoutMs;
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newCachedThreadPool(daemonThreadFactory("javi-otlp-http")))
                .build();
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("javi-otlp-retry-scheduler"));
    }

    public static OtlpHttpSender create() { return newBuilder().build(); }
    public static Builder newBuilder()    { return new Builder(); }

    // =========================================================================
    // 비동기 send — Worker 스레드를 블로킹하지 않음 (주 경로)
    // =========================================================================

    /**
     * 비동기 전송. Worker 스레드를 블로킹하지 않는다.
     *
     * <p>재시도는 {@link ScheduledExecutorService}를 통해 backoff 후 재귀적으로 예약된다.
     * 503 Retry-After 헤더가 있으면 그 값을 backoff에 반영한다.
     * 성공/최종 실패 시 반환된 CompletableFuture가 complete된다.
     *
     * @param endpointUri  전송 대상 URI
     * @param jsonBody     OTLP JSON 페이로드
     * @param extraHeaders 추가 헤더 (null 허용)
     * @return             전송 결과를 담은 CompletableFuture&lt;SendResult&gt;
     */
    public CompletableFuture<SendResult> sendAsync(URI endpointUri, String jsonBody,
                                                    Map<String, String> extraHeaders) {
        if (isShutdown.get()) return CompletableFuture.completedFuture(SendResult.SHUTDOWN);
        if (!checkCircuit())  return CompletableFuture.completedFuture(SendResult.CIRCUIT_OPEN);
        if (jsonBody == null || jsonBody.isEmpty())
            return CompletableFuture.completedFuture(SendResult.SUCCESS);

        CompletableFuture<SendResult> resultFuture = new CompletableFuture<>();
        executeAttemptAsync(endpointUri, jsonBody, extraHeaders, 0, resultFuture);
        return resultFuture;
    }

    /** extraHeaders 없는 단축 메서드. */
    public CompletableFuture<SendResult> sendAsync(URI endpointUri, String jsonBody) {
        return sendAsync(endpointUri, jsonBody, null);
    }

    /**
     * 단일 HTTP 시도를 비동기로 실행하고, 결과에 따라 재시도를 예약하거나 future를 완료한다.
     */
    private void executeAttemptAsync(URI uri, String body, Map<String, String> headers,
                                      int attempt, CompletableFuture<SendResult> resultFuture) {
        httpClient.sendAsync(buildRequest(uri, body, headers), HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, ex) -> {
                    if (isShutdown.get()) {
                        resultFuture.complete(SendResult.SHUTDOWN);
                        return;
                    }

                    if (ex != null) {
                        AgentLogger.warn("OTLP 비동기 전송 에러: " + ex.getMessage()
                                + " attempt=" + (attempt + 1));
                        scheduleRetryOrFail(uri, body, headers, attempt, resultFuture,
                                RETRY_BACKOFF_MS[Math.min(attempt + 1, RETRY_BACKOFF_MS.length - 1)]);
                        return;
                    }

                    int status = response.statusCode();

                    if (status >= 200 && status < 300) {
                        onSuccess();
                        resultFuture.complete(SendResult.SUCCESS);
                        return;
                    }

                    if (status >= 400 && status < 500) {
                        // 4xx: 페이로드 문제 — 재시도 불필요, circuit 대상 아님
                        AgentLogger.warn("OTLP 비동기 4xx: status=" + status + " endpoint=" + uri);
                        resultFuture.complete(SendResult.FAILURE);
                        return;
                    }

                    // 5xx: Retry-After 헤더를 우선 적용
                    long retryAfterMs = parseRetryAfter(response);
                    long backoffMs = retryAfterMs > 0
                            ? retryAfterMs
                            : RETRY_BACKOFF_MS[Math.min(attempt + 1, RETRY_BACKOFF_MS.length - 1)];

                    AgentLogger.warn("OTLP 비동기 5xx: status=" + status
                            + " attempt=" + (attempt + 1) + " nextBackoff=" + backoffMs + "ms");
                    scheduleRetryOrFail(uri, body, headers, attempt, resultFuture, backoffMs);
                });
    }

    private void scheduleRetryOrFail(URI uri, String body, Map<String, String> headers,
                                      int attempt, CompletableFuture<SendResult> resultFuture,
                                      long backoffMs) {
        int nextAttempt = attempt + 1;
        if (nextAttempt < MAX_ATTEMPTS) {
            retryScheduler.schedule(
                    () -> executeAttemptAsync(uri, body, headers, nextAttempt, resultFuture),
                    backoffMs,
                    TimeUnit.MILLISECONDS
            );
        } else {
            onFailure(uri);
            resultFuture.complete(SendResult.FAILURE);
        }
    }

    // =========================================================================
    // 동기 send — shutdown/flush 경로 등 블로킹이 허용될 때만 사용
    // =========================================================================

    /**
     * 동기 전송. 재시도 backoff 동안 호출 스레드를 블로킹한다.
     *
     * <p><b>주의:</b> Worker 스레드에서 호출하면 큐 overflow로 인한 데이터 손실이 발생할 수 있다.
     * 일반 실행 경로에서는 {@link #sendAsync}를 사용하라.
     */
    public SendResult send(URI endpointUri, String jsonBody, Map<String, String> extraHeaders) {
        if (isShutdown.get()) return SendResult.SHUTDOWN;
        if (!checkCircuit())  return SendResult.CIRCUIT_OPEN;
        if (jsonBody == null || jsonBody.isEmpty()) return SendResult.SUCCESS;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleepUninterruptibly(RETRY_BACKOFF_MS[attempt])) {
                return SendResult.FAILURE;
            }
            try {
                HttpResponse<Void> response = performHttpRequest(endpointUri, jsonBody, extraHeaders);
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    onSuccess();
                    return SendResult.SUCCESS;
                }
                if (status >= 400 && status < 500) {
                    AgentLogger.warn("OTLP 전송 실패 (4xx): status=" + status + " endpoint=" + endpointUri);
                    return SendResult.FAILURE;
                }
                // 5xx
                AgentLogger.warn("OTLP 전송 실패 (5xx/기타): status=" + status + " attempt=" + (attempt + 1));
                if (attempt == MAX_ATTEMPTS - 1) {
                    onFailure(endpointUri);
                }
            } catch (Exception e) {
                AgentLogger.warn("OTLP 전송 에러: " + e.getMessage() + " attempt=" + (attempt + 1));
                if (attempt == MAX_ATTEMPTS - 1) {
                    onFailure(endpointUri);
                }
            }
        }
        return SendResult.FAILURE;
    }

    /** extraHeaders 없는 단축 메서드. */
    public SendResult send(URI endpointUri, String jsonBody) {
        return send(endpointUri, jsonBody, null);
    }

    // =========================================================================
    // Circuit Breaker
    // =========================================================================

    /**
     * Circuit 상태를 확인한다.
     *
     * @return true이면 요청 진행 가능 (CLOSED 또는 Half-Open), false이면 OPEN
     */
    private boolean checkCircuit() {
        long openTime = lastOpenTime.get();
        if (openTime == 0) return true; // CLOSED

        long elapsed = System.currentTimeMillis() - openTime;
        if (elapsed < OPEN_DURATION_MS) {
            return false; // OPEN — 차단
        }
        // Half-Open — 탐침 요청 허용
        AgentLogger.debug("OTLP Circuit Half-Open: 복구 시도 중...");
        return true;
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        lastOpenTime.set(0); // CLOSED 상태로 전환
    }

    /**
     * 실패 처리 — Circuit Breaker 상태 갱신.
     *
     * <p><b>버그 수정 (Half-Open 재-오픈):</b><br>
     * 기존 {@code compareAndSet(0, now)}는 lastOpenTime이 이미 {@code > 0}인
     * Half-Open 상태에서 CAS가 항상 실패하여 circuit이 재-오픈되지 않았다.<br>
     * 수정 후 {@code compareAndSet(prev, now)}를 사용해 현재 값과 무관하게
     * lastOpenTime을 현재 시각으로 갱신한다. 이를 통해 Half-Open 실패 시
     * 30초 대기가 재시작된다.
     */
    private void onFailure(URI uri) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            long now  = System.currentTimeMillis();
            long prev = lastOpenTime.get();
            if (lastOpenTime.compareAndSet(prev, now)) {
                if (prev == 0) {
                    AgentLogger.warn("OTLP Circuit OPEN: 연속 " + failures
                            + "회 실패로 인해 " + (OPEN_DURATION_MS / 1000)
                            + "초간 전송이 차단됩니다. endpoint=" + uri);
                } else {
                    AgentLogger.warn("OTLP Circuit Half-Open 실패: " + (OPEN_DURATION_MS / 1000)
                            + "초 차단이 재시작됩니다. endpoint=" + uri);
                }
            }
            // CAS 실패 → 다른 스레드가 먼저 갱신한 것이므로 무시 (정합성 유지)
        }
    }

    // =========================================================================
    // HTTP 유틸
    // =========================================================================

    private HttpResponse<Void> performHttpRequest(URI uri, String body,
                                                   Map<String, String> headers) throws Exception {
        return httpClient.send(buildRequest(uri, body, headers),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpRequest buildRequest(URI uri, String body, Map<String, String> headers) {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        defaultHeaders.forEach(reqBuilder::header);
        if (headers != null) headers.forEach(reqBuilder::header);
        return reqBuilder.build();
    }

    /**
     * Retry-After 헤더를 파싱한다.
     *
     * <p>RFC 7231: Retry-After는 초 단위 정수 또는 HTTP-date 형식.
     * 정수(초)만 지원하며, HTTP-date 형식은 무시한다.
     *
     * @return 대기 시간(ms), 헤더 없거나 파싱 실패 시 0
     */
    static long parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(val -> {
            try {
                return Long.parseLong(val.trim()) * 1_000L;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    /** 리소스 해제. 이후 send()/sendAsync()는 SHUTDOWN을 반환한다. */
    public void shutdown() {
        isShutdown.set(true);
        retryScheduler.shutdown();
        AgentLogger.info("OtlpHttpSender shutdown 완료");
    }

    // =========================================================================
    // 설정 읽기 헬퍼
    // =========================================================================

    static String resolveBaseEndpoint() {
        return get(ENV_ENDPOINT, PROP_ENDPOINT, DEFAULT_BASE_ENDPOINT);
    }

    static long resolveTimeoutMs() {
        String raw = get(ENV_TIMEOUT_MS, PROP_TIMEOUT_MS, String.valueOf(DEFAULT_TIMEOUT_MS));
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            AgentLogger.warn("JAVI_COLLECTOR_TIMEOUT_MS 파싱 실패, 기본값 사용: " + raw);
            return DEFAULT_TIMEOUT_MS;
        }
    }

    public static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    // =========================================================================
    // 내부 유틸
    // =========================================================================

    /** 인터럽트 발생 시 false 반환, 정상 대기 완료 시 true 반환. */
    private static boolean sleepUninterruptibly(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static final class Builder {
        private long timeoutMs = resolveTimeoutMs();
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder() {
            headers.put("Content-Type", "application/json");
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public OtlpHttpSender build() {
            return new OtlpHttpSender(timeoutMs, headers);
        }
    }
}
