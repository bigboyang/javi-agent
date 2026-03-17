package com.agent.common.grpc;

import com.agent.logs.AgentLogger;
import com.agent.metric.MetricRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * OTLP/HTTP Protobuf 전송을 담당하는 공통 Transport.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>Java 11 HttpClient with HTTP/1.1 (cleartext, gRPC h2c Prior Knowledge 미지원 우회)</li>
 *   <li>protobuf 인코딩 유지 — JSON 오버헤드 없음</li>
 *   <li>Content-Type: application/x-protobuf (OTLP/HTTP 표준)</li>
 *   <li>gRPC 5-byte 프레이밍 없음 — raw protobuf bytes 직접 전송</li>
 *   <li>비동기 재시도: sendAsync() + ScheduledExecutorService (Thread.sleep 없음)</li>
 *   <li>Circuit Breaker: 연속 실패 5회 → OPEN, 30s 후 HALF_OPEN 프로브</li>
 *   <li>TLS/mTLS: JAVI_TLS_CA_PATH, JAVI_TLS_CLIENT_CERT_PATH, JAVI_TLS_CLIENT_KEY_PATH</li>
 * </ul>
 *
 * <p>설정 환경변수:
 * <ul>
 *   <li>JAVI_GRPC_ENDPOINT / javi.grpc.endpoint (기본: http://localhost:4318)</li>
 *   <li>JAVI_COLLECTOR_TIMEOUT_MS / javi.collector.timeout.ms (기본: 10000)</li>
 *   <li>JAVI_TLS_ENABLED — "true"면 TLS 강제 활성화 (https:// 엔드포인트는 자동)</li>
 *   <li>JAVI_TLS_CA_PATH — PEM CA 인증서 경로 (커스텀 CA 신뢰)</li>
 *   <li>JAVI_TLS_CLIENT_CERT_PATH — PEM 클라이언트 인증서 경로 (mTLS)</li>
 *   <li>JAVI_TLS_CLIENT_KEY_PATH — PKCS8 PEM 클라이언트 키 경로 (mTLS)</li>
 * </ul>
 */
public final class GrpcSender {

    private static final String ENV_GRPC_ENDPOINT  = "JAVI_GRPC_ENDPOINT";
    private static final String PROP_GRPC_ENDPOINT = "javi.grpc.endpoint";
    private static final String DEFAULT_ENDPOINT   = "http://localhost:4318";

    /**
     * 재시도 간격 (ms): attempt 0=즉시, 1=1s, 2=2s
     * Thread.sleep 대신 ScheduledExecutorService.schedule() 로 적용된다.
     */
    private static final long[] RETRY_BACKOFF_MS = {0L, 1_000L, 2_000L};

    /** 최대 재시도 횟수 */
    private static final int MAX_ATTEMPTS = 3;

    // ---- Circuit Breaker ----
    private enum CbState { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<CbState> cbState    = new AtomicReference<>(CbState.CLOSED);
    private final AtomicInteger            cbFailures  = new AtomicInteger(0);
    private volatile long                  cbOpenedAtMs = 0L;

    /** OPEN 전환까지 허용하는 연속 실패 횟수 */
    private static final int  CB_FAILURE_THRESHOLD = 5;
    /** OPEN 상태 유지 시간(ms) — 이후 HALF_OPEN 프로브 허용 */
    private static final long CB_COOLDOWN_MS       = 30_000L;

    public enum SendResult { SUCCESS, FAILURE, SHUTDOWN }

    private final String baseEndpoint;
    private final long   timeoutMs;
    private final HttpClient httpClient;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final Map<String, String> extraHeaders;
    private final boolean gzipEnabled;

    /**
     * 비동기 재시도 스케줄러 (Thread.sleep 대신 schedule() 사용).
     * 단일 데몬 스레드로 충분 — 재시도 지연 등록만 하며 실제 I/O는 HttpClient 풀이 담당.
     */
    private final ScheduledExecutorService retryScheduler;

    // ---- 생성자 ----

    private GrpcSender(String baseEndpoint, long timeoutMs) {
        this.baseEndpoint  = baseEndpoint.replaceAll("/+$", "");
        this.timeoutMs     = timeoutMs;
        this.extraHeaders  = parseHeaders();
        this.gzipEnabled   = resolveGzipEnabled();

        ThreadFactory httpDaemon = r -> {
            Thread t = new Thread(r, "javi-grpc-sender");
            t.setDaemon(true);
            return t;
        };
        ThreadFactory retryDaemon = r -> {
            Thread t = new Thread(r, "javi-grpc-retry");
            t.setDaemon(true);
            return t;
        };

        // retryScheduler: 재시도 지연 등록 전용 (단일 스레드)
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(retryDaemon);

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(2, httpDaemon));

        boolean tlsEnabled = this.baseEndpoint.startsWith("https://")
                || Boolean.parseBoolean(env("JAVI_TLS_ENABLED", "false"));
        if (tlsEnabled) {
            try {
                clientBuilder.sslContext(buildSslContext());
                AgentLogger.info("GrpcSender: TLS/mTLS 활성화");
            } catch (Exception e) {
                AgentLogger.warn("GrpcSender: TLS 설정 실패 — " + e.getMessage()
                        + " (plaintext 폴백)");
            }
        }
        this.httpClient = clientBuilder.build();
    }

    // ---- 팩토리 ----

    public static GrpcSender create() {
        return new GrpcSender(resolveEndpoint(), resolveTimeoutMs());
    }

    public static GrpcSender create(String baseEndpoint, long timeoutMs) {
        return new GrpcSender(baseEndpoint, timeoutMs);
    }

    // ---- 핵심 전송 (동기 래퍼) ----

    /**
     * gRPC 서비스 경로로 protobuf 페이로드를 전송한다. (동기 래퍼)
     *
     * <p>내부적으로 {@link #sendAsync}를 호출하고 결과를 대기한다.
     * 호출 스레드는 future.get() 에서만 대기하며, Thread.sleep() 은 사용하지 않는다.
     * 재시도 백오프 지연은 retryScheduler(별도 데몬 스레드)가 처리한다.
     *
     * @param grpcPath gRPC 서비스 경로
     *                 (예: /opentelemetry.proto.collector.trace.v1.TraceService/Export)
     * @param protoBody 직렬화된 protobuf bytes
     */
    public SendResult send(String grpcPath, byte[] protoBody) {
        if (isShutdown.get()) {
            AgentLogger.warn("GrpcSender: shutdown 상태 — 전송 중단 path=" + grpcPath);
            return SendResult.SHUTDOWN;
        }
        if (protoBody == null || protoBody.length == 0) {
            return SendResult.SUCCESS;
        }
        // 최대 대기: (timeoutMs + 최대 백오프) × 재시도 + 여유 1초
        long maxWaitMs = (timeoutMs + RETRY_BACKOFF_MS[MAX_ATTEMPTS - 1]) * MAX_ATTEMPTS + 1_000L;
        try {
            return sendAsync(grpcPath, protoBody).get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            AgentLogger.warn("GrpcSender: send timeout path=" + grpcPath);
            onSendFailure(grpcPath);
            return SendResult.FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.FAILURE;
        } catch (ExecutionException e) {
            AgentLogger.warn("GrpcSender: send execution error path=" + grpcPath
                    + " cause=" + unwrapCause(e));
            return SendResult.FAILURE;
        }
    }

    /**
     * gRPC 서비스 경로로 protobuf 페이로드를 비동기 전송한다.
     *
     * <p>재시도 백오프는 Thread.sleep 없이 {@code retryScheduler.schedule()}로 처리한다.
     * 반환된 CompletableFuture는 모든 재시도 완료 후 최종 {@link SendResult}로 완성된다.
     */
    public CompletableFuture<SendResult> sendAsync(String grpcPath, byte[] protoBody) {
        if (isShutdown.get()) {
            return CompletableFuture.completedFuture(SendResult.SHUTDOWN);
        }
        if (protoBody == null || protoBody.length == 0) {
            return CompletableFuture.completedFuture(SendResult.SUCCESS);
        }

        // ---- Circuit Breaker 체크 ----
        CbState state = cbState.get();
        if (state == CbState.OPEN) {
            long elapsed = System.currentTimeMillis() - cbOpenedAtMs;
            if (elapsed < CB_COOLDOWN_MS) {
                AgentLogger.debug("GrpcSender: circuit OPEN — 전송 차단 path=" + grpcPath
                        + " (쿨다운 남음=" + (CB_COOLDOWN_MS - elapsed) / 1000 + "s)");
                recordCbMetric(CbState.OPEN);
                return CompletableFuture.completedFuture(SendResult.FAILURE);
            }
            // 쿨다운 만료 → HALF_OPEN 프로브 허용 (CAS로 단 하나의 스레드만 전환)
            if (cbState.compareAndSet(CbState.OPEN, CbState.HALF_OPEN)) {
                AgentLogger.info("GrpcSender: circuit HALF_OPEN — 프로브 시작 path=" + grpcPath);
            }
        }

        byte[] body = maybeGzip(protoBody);
        CompletableFuture<SendResult> resultFuture = new CompletableFuture<>();
        doAttempt(grpcPath, body, 0, resultFuture);
        return resultFuture;
    }

    /**
     * 단일 HTTP 시도를 비동기로 실행한다.
     * 실패 시 retryScheduler 를 통해 다음 attempt 를 예약하므로 Thread.sleep() 이 없다.
     */
    private void doAttempt(String grpcPath, byte[] body,
                            int attempt, CompletableFuture<SendResult> resultFuture) {
        if (isShutdown.get()) {
            resultFuture.complete(SendResult.SHUTDOWN);
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            onSendFailure(grpcPath);
            resultFuture.complete(SendResult.FAILURE);
            return;
        }

        long delayMs = RETRY_BACKOFF_MS[attempt];

        Runnable sendTask = () -> {
            if (isShutdown.get()) {
                resultFuture.complete(SendResult.SHUTDOWN);
                return;
            }
            URI uri = URI.create(baseEndpoint + grpcPath);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("content-type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (gzipEnabled) {
                reqBuilder.header("content-encoding", "gzip");
            }
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                reqBuilder.header(h.getKey(), h.getValue());
            }

            httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, ex) -> {
                        if (isShutdown.get()) {
                            resultFuture.complete(SendResult.SHUTDOWN);
                            return;
                        }
                        if (ex != null) {
                            AgentLogger.warn("gRPC send error attempt=" + (attempt + 1)
                                    + "/" + MAX_ATTEMPTS + " path=" + grpcPath
                                    + " cause=" + unwrapCause(ex));
                            scheduleNextAttempt(grpcPath, body, attempt + 1, resultFuture);
                            return;
                        }
                        int status = response.statusCode();
                        if (status == 200) {
                            onSendSuccess(grpcPath, body.length);
                            resultFuture.complete(SendResult.SUCCESS);
                        } else if (status >= 400 && status < 500) {
                            AgentLogger.warn("gRPC send rejected (4xx=" + status
                                    + ") path=" + grpcPath + " — 재시도 없음");
                            // 4xx: 클라이언트 오류 — CB 카운트 대상 아님
                            resultFuture.complete(SendResult.FAILURE);
                        } else {
                            // 5xx / 기타 — 재시도 예약
                            AgentLogger.warn("gRPC send failed (status=" + status
                                    + ") attempt=" + (attempt + 1) + "/" + MAX_ATTEMPTS
                                    + " path=" + grpcPath);
                            scheduleNextAttempt(grpcPath, body, attempt + 1, resultFuture);
                        }
                    });
        };

        if (delayMs <= 0) {
            retryScheduler.execute(sendTask);
        } else {
            retryScheduler.schedule(sendTask, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 다음 attempt를 retryScheduler에 예약한다 (Thread.sleep 없음). */
    private void scheduleNextAttempt(String grpcPath, byte[] body,
                                      int nextAttempt, CompletableFuture<SendResult> resultFuture) {
        if (nextAttempt >= MAX_ATTEMPTS) {
            onSendFailure(grpcPath);
            resultFuture.complete(SendResult.FAILURE);
            return;
        }
        long delayMs = RETRY_BACKOFF_MS[nextAttempt];
        retryScheduler.schedule(
                () -> doAttempt(grpcPath, body, nextAttempt, resultFuture),
                delayMs, TimeUnit.MILLISECONDS);
    }

    private void onSendSuccess(String grpcPath, int bytes) {
        AgentLogger.debug("gRPC send success path=" + grpcPath + " bytes=" + bytes);
        cbFailures.set(0);
        CbState prev = cbState.getAndSet(CbState.CLOSED);
        if (prev != CbState.CLOSED) {
            AgentLogger.info("GrpcSender: circuit CLOSED (서킷 복구) path=" + grpcPath);
        }
        recordCbMetric(CbState.CLOSED);
    }

    private void onSendFailure(String grpcPath) {
        AgentLogger.warn("gRPC send exhausted retries path=" + grpcPath);
        // HALF_OPEN 프로브 실패 → 재오픈 (CAS 보호)
        if (cbState.compareAndSet(CbState.HALF_OPEN, CbState.OPEN)) {
            cbOpenedAtMs = System.currentTimeMillis();
            cbFailures.set(0);
            AgentLogger.warn("GrpcSender: circuit re-OPEN (HALF_OPEN 프로브 실패)");
            recordCbMetric(CbState.OPEN);
            return;
        }
        // CLOSED 상태 실패 카운트 누적 → 임계치 도달 시 OPEN
        int failures = cbFailures.incrementAndGet();
        if (failures >= CB_FAILURE_THRESHOLD && cbState.compareAndSet(CbState.CLOSED, CbState.OPEN)) {
            cbOpenedAtMs = System.currentTimeMillis();
            cbFailures.set(0);
            AgentLogger.warn("GrpcSender: circuit OPEN (연속 실패 " + CB_FAILURE_THRESHOLD
                    + "회 초과) path=" + grpcPath);
        }
        recordCbMetric(cbState.get());
    }

    private void recordCbMetric(CbState state) {
        try {
            int stateVal = state == CbState.CLOSED ? 0 : state == CbState.HALF_OPEN ? 1 : 2;
            MetricRegistry.get()
                    .gauge("javi.grpc.circuit_breaker.state", Collections.emptyMap())
                    .set(stateVal);
            MetricRegistry.get()
                    .gauge("javi.grpc.circuit_breaker.failures", Collections.emptyMap())
                    .set(cbFailures.get());
        } catch (Exception ignored) {
            // 메트릭 기록 실패는 전송 결과에 영향 없음
        }
    }

    public void shutdown() {
        isShutdown.set(true);
        retryScheduler.shutdown();
        try {
            // 진행 중인 재시도 완료 대기 (최대 5초)
            if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryScheduler.shutdownNow();
        }
        AgentLogger.info("GrpcSender shutdown 완료");
    }

    // ---- 헤더/압축 파싱 ----

    /**
     * JAVI_OTLP_HEADERS 또는 OTEL_EXPORTER_OTLP_HEADERS 환경변수에서 커스텀 헤더를 파싱한다.
     * 형식: "key1=value1,key2=value2" (Datadog, Grafana Cloud 등에서 사용)
     */
    private static Map<String, String> parseHeaders() {
        String raw = System.getenv("JAVI_OTLP_HEADERS");
        if (raw == null || raw.isEmpty()) raw = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
        if (raw == null || raw.isEmpty()) raw = System.getProperty("javi.otlp.headers", "");
        if (raw.isEmpty()) return Collections.emptyMap();

        Map<String, String> headers = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                headers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * JAVI_OTLP_COMPRESSION 또는 OTEL_EXPORTER_OTLP_COMPRESSION 환경변수로 gzip 활성화 여부를 결정한다.
     * 값: "gzip" → 활성화, 그 외 → 비활성화 (기본값 none)
     */
    private static boolean resolveGzipEnabled() {
        String val = System.getenv("JAVI_OTLP_COMPRESSION");
        if (val == null || val.isEmpty()) val = System.getenv("OTEL_EXPORTER_OTLP_COMPRESSION");
        if (val == null || val.isEmpty()) val = System.getProperty("javi.otlp.compression", "none");
        return "gzip".equalsIgnoreCase(val);
    }

    private byte[] maybeGzip(byte[] data) {
        if (!gzipEnabled) return data;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
            try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
                gzos.write(data);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            AgentLogger.warn("GrpcSender: gzip 압축 실패, raw bytes 전송: " + e.getMessage());
            return data;
        }
    }

    // ---- TLS/mTLS 설정 ----

    /**
     * 환경변수로부터 SSLContext를 구성한다.
     *
     * <p>지원 시나리오:
     * <ol>
     *   <li>기본 TLS — 시스템 기본 TrustStore 사용 (JDK 번들 CA 신뢰)</li>
     *   <li>커스텀 CA — JAVI_TLS_CA_PATH 지정 시 해당 PEM CA를 신뢰</li>
     *   <li>mTLS — JAVI_TLS_CLIENT_CERT_PATH + JAVI_TLS_CLIENT_KEY_PATH 지정 시 클라이언트 인증서 사용</li>
     * </ol>
     */
    private static SSLContext buildSslContext() throws Exception {
        String caPath   = env("JAVI_TLS_CA_PATH", null);
        String certPath = env("JAVI_TLS_CLIENT_CERT_PATH", null);
        String keyPath  = env("JAVI_TLS_CLIENT_KEY_PATH", null);

        TrustManagerFactory tmf = null;
        if (caPath != null && !caPath.isEmpty()) {
            byte[] caDer = decodePem(new String(Files.readAllBytes(Paths.get(caPath))));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate caCert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(caDer));
            KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
            ts.load(null, null);
            ts.setCertificateEntry("javi-ca", caCert);
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            AgentLogger.info("GrpcSender: 커스텀 CA 신뢰 설정 완료 path=" + caPath);
        }

        KeyManagerFactory kmf = null;
        if (certPath != null && !certPath.isEmpty() && keyPath != null && !keyPath.isEmpty()) {
            byte[] certDer = decodePem(new String(Files.readAllBytes(Paths.get(certPath))));
            byte[] keyDer  = decodePem(new String(Files.readAllBytes(Paths.get(keyPath))));

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate clientCert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(certDer));

            PrivateKey privateKey = loadPrivateKey(keyDer);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setKeyEntry("javi-client", privateKey, new char[0],
                    new X509Certificate[]{ clientCert });
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            AgentLogger.info("GrpcSender: mTLS 클라이언트 인증서 설정 완료 cert=" + certPath);
        }

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(
                kmf != null ? kmf.getKeyManagers() : null,
                tmf != null ? tmf.getTrustManagers() : null,
                null);
        return ssl;
    }

    /** PEM 파일에서 Base64 DER 바이트를 추출한다 (헤더/푸터 제거). */
    private static byte[] decodePem(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-----")) {
                sb.append(trimmed);
            }
        }
        return Base64.getDecoder().decode(sb.toString());
    }

    /** PKCS8 DER 바이트에서 PrivateKey를 로드한다 (RSA → EC 순서로 시도). */
    private static PrivateKey loadPrivateKey(byte[] der) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algo : new String[]{ "RSA", "EC", "DSA" }) {
            try {
                return KeyFactory.getInstance(algo).generatePrivate(spec);
            } catch (Exception ignored) {
                // 다음 알고리즘 시도
            }
        }
        throw new IllegalArgumentException("지원되지 않는 Private Key 알고리즘 (RSA/EC/DSA 중 하나여야 함)");
    }

    // ---- 설정 읽기 ----

    public static String resolveEndpoint() {
        String val = System.getenv(ENV_GRPC_ENDPOINT);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(PROP_GRPC_ENDPOINT);
        if (val != null && !val.isEmpty()) return val;
        return DEFAULT_ENDPOINT;
    }

    private static long resolveTimeoutMs() {
        String raw = System.getenv("JAVI_COLLECTOR_TIMEOUT_MS");
        if (raw == null) raw = System.getProperty("javi.collector.timeout.ms", "10000");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 10_000L;
        }
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(key.toLowerCase().replace('_', '.'));
        return (val != null && !val.isEmpty()) ? val : defaultVal;
    }

    private static String unwrapCause(Throwable ex) {
        Throwable cause = ex.getCause();
        return cause != null
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
}
