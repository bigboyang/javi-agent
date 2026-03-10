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

    public enum SendResult { SUCCESS, FAILURE, SHUTDOWN }

    // ---- 상태 ----
    private final HttpClient httpClient;
    private final long timeoutMs;
    private final Map<String, String> defaultHeaders;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // ---- 내부 생성자 — Builder 또는 팩토리를 통해서만 생성 ----

    private OtlpHttpSender(long timeoutMs, Map<String, String> defaultHeaders) {
        this.timeoutMs = timeoutMs;
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(defaultHeaders));

        ThreadFactory daemon = r -> {
            Thread t = new Thread(r, "javi-otlp-sender");
            t.setDaemon(true);
            return t;
        };
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 전송 전용 스레드 풀: 블로킹 I/O가 메인 스레드를 점유하지 않도록 분리
                .executor(Executors.newFixedThreadPool(2, daemon))
                .build();
    }

    // ---- 팩토리 메서드 ----

    /**
     * 환경변수/시스템 프로퍼티에서 설정을 읽어 인스턴스를 생성한다.
     * <p>추가 헤더 없이 기본 Content-Type만 사용한다.
     */
    public static OtlpHttpSender create() {
        return builder().build();
    }

    /** 커스텀 설정이 필요할 때 사용하는 Builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 환경변수/시스템 프로퍼티에서 설정을 읽어 인증 헤더가 포함된 인스턴스를 생성한다.
     *
     * <p>지원하는 헤더 환경변수 (우선순위 순):
     * <ol>
     *   <li>{@code JAVI_OTLP_HEADERS} — javi 전용 (예: {@code Authorization=Bearer token,X-Scope=tenant})</li>
     *   <li>{@code OTEL_EXPORTER_OTLP_HEADERS} — OTel 표준 형식</li>
     * </ol>
     * 형식: {@code key1=value1,key2=value2} (URL-form-encoded 지원 안 함)
     */
    public static OtlpHttpSender createWithAuth() {
        Builder builder = builder();

        // JAVI_OTLP_HEADERS 우선
        String rawHeaders = System.getenv("JAVI_OTLP_HEADERS");
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            rawHeaders = System.getProperty("javi.otlp.headers");
        }
        // OTel 표준 fallback
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            rawHeaders = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
        }

        if (rawHeaders != null && !rawHeaders.isEmpty()) {
            for (String pair : rawHeaders.split(",")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = pair.substring(0, idx).trim();
                    String val = pair.substring(idx + 1).trim();
                    if (!key.isEmpty()) {
                        builder.header(key, val);
                    }
                }
            }
        }

        return builder.build();
    }

    // ---- 핵심 전송 메서드 ----

    /**
     * 지정된 엔드포인트 경로로 JSON 페이로드를 전송한다.
     *
     * @param endpointUri 전체 URI (예: http://localhost:4318/v1/traces)
     * @param jsonBody    직렬화된 OTLP JSON 문자열
     * @param extraHeaders 요청별 추가 헤더 (null 허용)
     * @return 전송 결과
     */
    public SendResult send(URI endpointUri, String jsonBody, Map<String, String> extraHeaders) {
        if (isShutdown.get()) {
            AgentLogger.warn("OtlpHttpSender: 이미 shutdown 상태 — 전송 중단 endpoint=" + endpointUri);
            return SendResult.SHUTDOWN;
        }
        if (jsonBody == null || jsonBody.isEmpty()) {
            return SendResult.SUCCESS; // 빈 배치는 성공으로 처리
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                if (!sleepUninterruptibly(RETRY_BACKOFF_MS[attempt])) {
                    return SendResult.FAILURE; // 인터럽트 — 즉시 포기
                }
            }

            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(endpointUri)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

                // 기본 헤더 적용
                for (Map.Entry<String, String> h : defaultHeaders.entrySet()) {
                    reqBuilder.header(h.getKey(), h.getValue());
                }
                // 요청별 추가 헤더 (기본 헤더를 덮어쓸 수 있음)
                if (extraHeaders != null) {
                    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                        reqBuilder.header(h.getKey(), h.getValue());
                    }
                }

                HttpResponse<Void> response = httpClient.send(
                        reqBuilder.build(),
                        HttpResponse.BodyHandlers.discarding()
                );

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    AgentLogger.debug("OTLP send success status=" + status + " endpoint=" + endpointUri
                            + " bytes=" + jsonBody.length());
                    return SendResult.SUCCESS;
                }

                // 4xx: 페이로드 자체 문제 — 재시도해도 의미 없음
                if (status >= 400 && status < 500) {
                    AgentLogger.warn("OTLP send rejected (4xx=" + status + ") endpoint=" + endpointUri
                            + " — 재시도 없음");
                    return SendResult.FAILURE;
                }

                // 5xx: 서버 일시 오류 — 재시도 가능
                AgentLogger.warn("OTLP send failed (5xx=" + status + ") attempt=" + (attempt + 1)
                        + "/" + MAX_ATTEMPTS + " endpoint=" + endpointUri);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                AgentLogger.warn("OTLP send interrupted endpoint=" + endpointUri);
                return SendResult.FAILURE;
            } catch (Exception e) {
                AgentLogger.warn("OTLP send error attempt=" + (attempt + 1) + "/" + MAX_ATTEMPTS
                        + " endpoint=" + endpointUri + " cause=" + e.getMessage());
            }
        }

        AgentLogger.warn("OTLP send exhausted retries endpoint=" + endpointUri);
        return SendResult.FAILURE;
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
