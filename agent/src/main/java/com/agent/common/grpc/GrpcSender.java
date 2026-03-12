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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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
 *   <li>지수 백오프 재시도: 5xx / 네트워크 오류에만 적용, 4xx는 즉시 실패</li>
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

    /** 재시도 간격 (ms): attempt 0=즉시, 1=1s, 2=2s (Circuit Breaker가 빠른 실패를 담당) */
    private static final long[] RETRY_BACKOFF_MS = {0L, 1_000L, 2_000L};

    /** 최대 재시도 횟수 (Circuit Breaker 적용 시 실질적으로 단축됨) */
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

    // ---- 생성자 ----

    private GrpcSender(String baseEndpoint, long timeoutMs) {
        this.baseEndpoint  = baseEndpoint.replaceAll("/+$", "");
        this.timeoutMs     = timeoutMs;
        this.extraHeaders  = parseHeaders();
        this.gzipEnabled   = resolveGzipEnabled();

        ThreadFactory daemon = r -> {
            Thread t = new Thread(r, "javi-grpc-sender");
            t.setDaemon(true);
            return t;
        };
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(2, daemon));

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

    // ---- 핵심 전송 ----

    /**
     * gRPC 서비스 경로로 protobuf 페이로드를 전송한다.
     *
     * @param grpcPath gRPC 서비스 경로
     *                 (예: /opentelemetry.proto.collector.trace.v1.TraceService/Export)
     * @param protoBody 직렬화된 protobuf bytes (5-byte 프레임 전 raw proto)
     */
    public SendResult send(String grpcPath, byte[] protoBody) {
        if (isShutdown.get()) {
            AgentLogger.warn("GrpcSender: shutdown 상태 — 전송 중단 path=" + grpcPath);
            return SendResult.SHUTDOWN;
        }
        if (protoBody == null || protoBody.length == 0) {
            return SendResult.SUCCESS;
        }

        // ---- Circuit Breaker 체크 ----
        CbState state = cbState.get();
        if (state == CbState.OPEN) {
            long elapsed = System.currentTimeMillis() - cbOpenedAtMs;
            if (elapsed < CB_COOLDOWN_MS) {
                AgentLogger.debug("GrpcSender: circuit OPEN — 전송 차단 path=" + grpcPath
                        + " (쿨다운 남음=" + (CB_COOLDOWN_MS - elapsed) / 1000 + "s)");
                recordCbMetric(CbState.OPEN);
                return SendResult.FAILURE;
            }
            // 쿨다운 만료 → HALF_OPEN 프로브 허용 (CAS로 단 하나의 스레드만 전환)
            if (cbState.compareAndSet(CbState.OPEN, CbState.HALF_OPEN)) {
                AgentLogger.info("GrpcSender: circuit HALF_OPEN — 프로브 시작 path=" + grpcPath);
            }
        }

        URI uri = URI.create(baseEndpoint + grpcPath);
        byte[] body = maybeGzip(protoBody);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleepUninterruptibly(RETRY_BACKOFF_MS[attempt])) {
                return SendResult.FAILURE;
            }
            try {
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
                HttpRequest request = reqBuilder.build();

                HttpResponse<Void> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.discarding());

                int status = response.statusCode();

                if (status == 200) {
                    // 성공 → Circuit Breaker 리셋
                    onSendSuccess(grpcPath, body.length);
                    return SendResult.SUCCESS;
                }

                if (status >= 400 && status < 500) {
                    AgentLogger.warn("gRPC send rejected (4xx=" + status + ") path=" + grpcPath
                            + " — 재시도 없음");
                    // 4xx는 서버 거부 (클라이언트 오류) — CB 카운트 대상 아님
                    return SendResult.FAILURE;
                }

                AgentLogger.warn("gRPC send failed (status=" + status + ") attempt="
                        + (attempt + 1) + "/" + MAX_ATTEMPTS + " path=" + grpcPath);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                AgentLogger.warn("gRPC send interrupted path=" + grpcPath);
                return SendResult.FAILURE;
            } catch (Exception e) {
                AgentLogger.warn("gRPC send error attempt=" + (attempt + 1) + "/" + MAX_ATTEMPTS
                        + " path=" + grpcPath + " cause=" + e.getMessage());
            }
        }

        // 모든 재시도 소진 → Circuit Breaker 실패 카운트 업데이트
        onSendFailure(grpcPath);
        return SendResult.FAILURE;
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
        CbState current = cbState.get();
        if (current == CbState.HALF_OPEN) {
            // HALF_OPEN 프로브 실패 → 재오픈
            cbState.set(CbState.OPEN);
            cbOpenedAtMs = System.currentTimeMillis();
            cbFailures.set(0);
            AgentLogger.warn("GrpcSender: circuit re-OPEN (HALF_OPEN 프로브 실패)");
            recordCbMetric(CbState.OPEN);
            return;
        }
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
}
