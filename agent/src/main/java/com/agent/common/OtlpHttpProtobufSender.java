package com.agent.common;

import com.agent.logs.AgentLogger;
import com.agent.metric.MetricRegistry;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OTLP/HTTP Protobuf 전송을 담당하는 공통 Transport.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>Java 11 HttpClient 사용 (HTTP/1.1)</li>
 *   <li>Protobuf 인코딩 유지 — JSON 오버헤드 없음</li>
 *   <li>Content-Type: application/x-protobuf (OTLP/HTTP 표준)</li>
 *   <li>비동기 재시도: sendAsync() + ScheduledExecutorService</li>
 *   <li>Circuit Breaker 패턴 내장</li>
 * </ul>
 */
public final class OtlpHttpProtobufSender {

    private static final String ENV_ENDPOINT    = "JAVI_COLLECTOR_ENDPOINT";
    private static final String PROP_ENDPOINT   = "javi.collector.endpoint";
    private static final String DEFAULT_ENDPOINT = "http://localhost:4318";

    private static final long[] RETRY_BACKOFF_MS = {0L, 1_000L, 2_000L};
    private static final int MAX_ATTEMPTS = 3;

    private enum CbState { CLOSED, OPEN, HALF_OPEN }
    private final AtomicReference<CbState> cbState    = new AtomicReference<>(CbState.CLOSED);
    private final AtomicInteger            cbFailures  = new AtomicInteger(0);
    private volatile long                  cbOpenedAtMs = 0L;
    private static final int  CB_FAILURE_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS       = 30_000L;

    public enum SendResult { SUCCESS, FAILURE, SHUTDOWN }

    private final String baseEndpoint;
    private final long   timeoutMs;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final Map<String, String> extraHeaders;
    private final boolean gzipEnabled;
    private final ScheduledExecutorService retryScheduler;

    private OtlpHttpProtobufSender(String baseEndpoint, long timeoutMs) {
        this.baseEndpoint  = baseEndpoint.replaceAll("/+$", "");
        this.timeoutMs     = timeoutMs;
        this.extraHeaders  = parseHeaders();
        this.gzipEnabled   = resolveGzipEnabled();

        ThreadFactory httpDaemon = r -> {
            Thread t = new Thread(r, "javi-otlp-sender");
            t.setDaemon(true);
            return t;
        };
        ThreadFactory retryDaemon = r -> {
            Thread t = new Thread(r, "javi-otlp-retry");
            t.setDaemon(true);
            return t;
        };

        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(retryDaemon);
        this.httpExecutor = Executors.newFixedThreadPool(2, httpDaemon);

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(this.httpExecutor)
                .build();
    }

    public static OtlpHttpProtobufSender create() {
        return new OtlpHttpProtobufSender(resolveEndpoint(), resolveTimeoutMs());
    }

    public static OtlpHttpProtobufSender create(String baseEndpoint, long timeoutMs) {
        return new OtlpHttpProtobufSender(baseEndpoint, timeoutMs);
    }

    public SendResult send(String path, byte[] protoBody) {
        if (isShutdown.get()) return SendResult.SHUTDOWN;
        if (protoBody == null || protoBody.length == 0) return SendResult.SUCCESS;

        long maxWaitMs = (timeoutMs + RETRY_BACKOFF_MS[MAX_ATTEMPTS - 1]) * MAX_ATTEMPTS + 1_000L;
        try {
            return sendAsync(path, protoBody).get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpProtobufSender: 전송 오류 path=" + path + " cause=" + e.getMessage());
            return SendResult.FAILURE;
        }
    }

    public CompletableFuture<SendResult> sendAsync(String path, byte[] protoBody) {
        if (isShutdown.get()) return CompletableFuture.completedFuture(SendResult.SHUTDOWN);
        if (protoBody == null || protoBody.length == 0) return CompletableFuture.completedFuture(SendResult.SUCCESS);

        CbState state = cbState.get();
        if (state == CbState.OPEN) {
            long elapsed = System.currentTimeMillis() - cbOpenedAtMs;
            if (elapsed < CB_COOLDOWN_MS) return CompletableFuture.completedFuture(SendResult.FAILURE);
            cbState.compareAndSet(CbState.OPEN, CbState.HALF_OPEN);
        }

        byte[] body = maybeGzip(protoBody);
        CompletableFuture<SendResult> resultFuture = new CompletableFuture<>();
        doAttempt(path, body, 0, resultFuture);
        return resultFuture;
    }

    private void doAttempt(String path, byte[] body, int attempt, CompletableFuture<SendResult> resultFuture) {
        if (isShutdown.get()) { resultFuture.complete(SendResult.SHUTDOWN); return; }
        if (attempt >= MAX_ATTEMPTS) { onSendFailure(path); resultFuture.complete(SendResult.FAILURE); return; }

        long jitter = attempt > 0 ? (long) (Math.random() * 500L) : 0L;
        long delayMs = RETRY_BACKOFF_MS[attempt] + jitter;
        retryScheduler.schedule(() -> {
            if (isShutdown.get()) { resultFuture.complete(SendResult.SHUTDOWN); return; }
            
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseEndpoint + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (gzipEnabled) reqBuilder.header("Content-Encoding", "gzip");
            extraHeaders.forEach(reqBuilder::header);

            httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, ex) -> {
                        if (ex != null) {
                            scheduleNextAttempt(path, body, attempt + 1, resultFuture);
                        } else if (response.statusCode() == 200) {
                            onSendSuccess(path, body.length);
                            resultFuture.complete(SendResult.SUCCESS);
                        } else if (response.statusCode() >= 400 && response.statusCode() < 500) {
                            resultFuture.complete(SendResult.FAILURE);
                        } else {
                            scheduleNextAttempt(path, body, attempt + 1, resultFuture);
                        }
                    });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleNextAttempt(String path, byte[] body, int nextAttempt, CompletableFuture<SendResult> resultFuture) {
        if (nextAttempt >= MAX_ATTEMPTS) {
            onSendFailure(path);
            resultFuture.complete(SendResult.FAILURE);
        } else {
            doAttempt(path, body, nextAttempt, resultFuture);
        }
    }

    private void onSendSuccess(String path, int bytes) {
        cbFailures.set(0);
        cbState.set(CbState.CLOSED);
        recordCbMetric(CbState.CLOSED);
    }

    private void onSendFailure(String path) {
        if (cbState.compareAndSet(CbState.HALF_OPEN, CbState.OPEN)) {
            cbOpenedAtMs = System.currentTimeMillis();
            cbFailures.set(0);
        } else if (cbFailures.incrementAndGet() >= CB_FAILURE_THRESHOLD &&
                   cbState.compareAndSet(CbState.CLOSED, CbState.OPEN)) {
            cbOpenedAtMs = System.currentTimeMillis();
            cbFailures.set(0);
        }
        recordCbMetric(cbState.get());
    }

    private void recordCbMetric(CbState state) {
        try {
            int val = state == CbState.CLOSED ? 0 : state == CbState.HALF_OPEN ? 1 : 2;
            MetricRegistry.get().gauge("javi.otlp.circuit_breaker.state", Collections.emptyMap()).set(val);
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        isShutdown.set(true);
        retryScheduler.shutdown();
        httpExecutor.shutdown();
    }

    private static Map<String, String> parseHeaders() {
        String raw = System.getenv("JAVI_OTLP_HEADERS");
        if (raw == null || raw.isEmpty()) raw = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();

        Map<String, String> headers = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) headers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return Collections.unmodifiableMap(headers);
    }

    private static boolean resolveGzipEnabled() {
        String val = System.getenv("JAVI_OTLP_COMPRESSION");
        if (val == null || val.isEmpty()) val = System.getenv("OTEL_EXPORTER_OTLP_COMPRESSION");
        return "gzip".equalsIgnoreCase(val);
    }

    private byte[] maybeGzip(byte[] data) {
        if (!gzipEnabled) return data;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
            gzos.write(data);
            gzos.finish();
            return baos.toByteArray();
        } catch (Exception e) { return data; }
    }

    public static String resolveEndpoint() {
        String val = System.getenv(ENV_ENDPOINT);
        return (val != null && !val.isEmpty()) ? val : System.getProperty(PROP_ENDPOINT, DEFAULT_ENDPOINT);
    }

    private static long resolveTimeoutMs() {
        try {
            return Long.parseLong(System.getenv("JAVI_COLLECTOR_TIMEOUT_MS"));
        } catch (Exception e) { return 10_000L; }
    }

}
