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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTLP/HTTP JSON 전송을 담당하는 공통 유틸리티.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>Java 11 HttpClient 사용 — 외부 라이브러리 없음</li>
 *   <li>지수 백오프 재시도: 5xx / 네트워크 오류에만 적용, 4xx는 즉시 실패</li>
 *   <li>타임아웃: 연결 5초 고정, 요청 타임아웃은 설정 주입</li>
 *   <li>헤더: 기본 Content-Type + 사용자 정의 헤더 병합</li>
 *   <li>shutdown 이후 send() 호출은 즉시 FAILURE 반환</li>
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
    private static final int FAILURE_THRESHOLD = 5;       // 연속 5회 실패 시 차단
    private static final long OPEN_DURATION_MS = 30_000L; // 30초간 차단

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastOpenTime = new java.util.concurrent.atomic.AtomicLong(0);

    // ---- 핵심 전송 메서드 ----

    public SendResult send(URI endpointUri, String jsonBody, Map<String, String> extraHeaders) {
        if (isShutdown.get()) {
            return SendResult.SHUTDOWN;
        }

        // Circuit Breaker 체크
        long openTime = lastOpenTime.get();
        if (openTime > 0) {
            if (System.currentTimeMillis() - openTime < OPEN_DURATION_MS) {
                return SendResult.CIRCUIT_OPEN; // 회로 오픈 상태
            } else {
                // Half-Open: 시도해보기 위해 상태 초기화는 하지 않고 일단 통과
                AgentLogger.debug("OTLP Circuit Half-Open: 복구 시도 중... endpoint=" + endpointUri);
            }
        }

        if (jsonBody == null || jsonBody.isEmpty()) {
            return SendResult.SUCCESS;
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            // ... (기존 retry 로직 생략을 위해 내부 로직 유지하되 결과에 따라 상태 업데이트)
            try {
                // (기존 전송 코드 호출 부분)
                HttpResponse<Void> response = performHttpRequest(endpointUri, jsonBody, extraHeaders);
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    onSuccess();
                    return SendResult.SUCCESS;
                }

                if (status >= 400 && status < 500) {
                    // 4xx는 서버 거부이므로 회로 차단 대상 아님 (페이로드 문제)
                    return SendResult.FAILURE;
                }
                
                // 5xx 또는 기타 오류
                onFailure(endpointUri);
            } catch (Exception e) {
                onFailure(endpointUri);
            }
        }
        return SendResult.FAILURE;
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        lastOpenTime.set(0);
    }

    private void onFailure(URI uri) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            if (lastOpenTime.compareAndSet(0, System.currentTimeMillis())) {
                AgentLogger.warn("OTLP Circuit OPEN: 연속 실패로 인해 전송이 30초간 차단됩니다. endpoint=" + uri);
            }
        }
    }

    // (기본 전송 로직을 분리하여 호출)
    private HttpResponse<Void> performHttpRequest(URI uri, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        
        defaultHeaders.forEach(reqBuilder::header);
        if (headers != null) headers.forEach(reqBuilder::header);

        return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
    }

    /** extraHeaders 없는 단축 메서드. */
    public SendResult send(URI endpointUri, String jsonBody) {
        return send(endpointUri, jsonBody, null);
    }

    /** 리소스 해제. 이후 send()는 SHUTDOWN을 반환한다. */
    public void shutdown() {
        isShutdown.set(true);
        // Java 11 HttpClient는 명시적 close가 없으므로 GC에 위임.
        // executor는 daemon=true이므로 JVM 종료 시 자동 해제된다.
        AgentLogger.info("OtlpHttpSender shutdown 완료");
    }

    // ---- 설정 읽기 헬퍼 ----

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

    // ---- 내부 유틸 ----

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

    // ---- Builder ----

    public static final class Builder {
        private long timeoutMs = resolveTimeoutMs();
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder() {
            // 기본 헤더
            headers.put("Content-Type", "application/json");
        }

        /** 요청 타임아웃(ms)을 명시적으로 지정한다. */
        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * 모든 요청에 포함할 커스텀 헤더를 추가한다.
         * 예: Authorization, X-Scope-OrgID 등
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public OtlpHttpSender build() {
            return new OtlpHttpSender(timeoutMs, headers);
        }

        // static 접근을 위한 위임 메서드
        private static long resolveTimeoutMs() {
            return OtlpHttpSender.resolveTimeoutMs();
        }
    }
}
