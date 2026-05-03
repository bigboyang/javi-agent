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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
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
    private static final long DEFAULT_RETRY_AFTER_MS = 5_000L;

    private enum CbState { CLOSED, OPEN, HALF_OPEN }
    private final AtomicReference<CbState> cbState    = new AtomicReference<>(CbState.CLOSED);
    private final AtomicInteger            cbFailures  = new AtomicInteger(0);
    private volatile long                  cbOpenedAtMs = 0L;
    private static final int  CB_FAILURE_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS       = 30_000L;
    private static final int  CB_QUEUE_CAPACITY    = 16;

    public enum SendResult { SUCCESS, FAILURE, SHUTDOWN }

    private static final class PendingPayload {
        final String path;
        final byte[] protoBody;
        final CompletableFuture<SendResult> future;
        PendingPayload(String p, byte[] b, CompletableFuture<SendResult> f) {
            path = p; protoBody = b; future = f;
        }
    }

    private final String baseEndpoint;
    private final long   timeoutMs;
    private final String signalType;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final Map<String, String> extraHeaders;
    private final boolean gzipEnabled;
    private final ScheduledExecutorService retryScheduler;
    private final ArrayBlockingQueue<PendingPayload> cbPendingQueue = new ArrayBlockingQueue<>(CB_QUEUE_CAPACITY);
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    private OtlpHttpProtobufSender(String baseEndpoint, long timeoutMs, String signalType) {
        this.baseEndpoint  = baseEndpoint.replaceAll("/+$", "");
        this.timeoutMs     = timeoutMs;
        this.signalType    = (signalType != null && !signalType.isEmpty()) ? signalType : "unknown";
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
        // bounded queue: prevents unbounded task accumulation under sustained failures
        this.httpExecutor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1000),
                httpDaemon,
                new ThreadPoolExecutor.DiscardOldestPolicy());

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(this.httpExecutor)
                .build();
    }

    public static OtlpHttpProtobufSender create() {
        return new OtlpHttpProtobufSender(resolveEndpoint(), resolveTimeoutMs(), "traces");
    }

    public static OtlpHttpProtobufSender create(String signalType) {
        return new OtlpHttpProtobufSender(resolveEndpoint(), resolveTimeoutMs(), signalType);
    }

    public static OtlpHttpProtobufSender create(String baseEndpoint, long timeoutMs) {
        return new OtlpHttpProtobufSender(baseEndpoint, timeoutMs, "traces");
    }

    public static OtlpHttpProtobufSender create(String baseEndpoint, long timeoutMs, String signalType) {
        return new OtlpHttpProtobufSender(baseEndpoint, timeoutMs, signalType);
    }

    public SendResult send(String path, byte[] protoBody) {
        if (isShutdown.get()) return SendResult.SHUTDOWN;
        if (protoBody == null || protoBody.length == 0) return SendResult.SUCCESS;

        long maxWaitMs = (timeoutMs + RETRY_BACKOFF_MS[MAX_ATTEMPTS - 1]) * MAX_ATTEMPTS + 1_000L;
        try {
            return sendAsync(path, protoBody).get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AgentLogger.warn("OtlpHttpProtobufSender: 전송 인터럽트 path=" + path);
            return SendResult.FAILURE;
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
            if (elapsed < CB_COOLDOWN_MS) {
                // CB 쿨다운 중 즉시 드롭 대신 버퍼링 — 쿨다운 만료 후 drain에서 재시도
                CompletableFuture<SendResult> pending = new CompletableFuture<>();
                if (!isShutdown.get() && cbPendingQueue.offer(new PendingPayload(path, protoBody, pending))) {
                    scheduleCbDrain(CB_COOLDOWN_MS - elapsed);
                    recordCounter("javi.otlp.cb.buffered", 1);
                    return pending;
                }
                recordCounter("javi.otlp.cb.dropped", 1);
                return CompletableFuture.completedFuture(SendResult.FAILURE);
            }
            // CAS 실패 시 다른 스레드가 이미 HALF_OPEN 전환 — 프로브는 하나만 허용
            if (!cbState.compareAndSet(CbState.OPEN, CbState.HALF_OPEN)) {
                return CompletableFuture.completedFuture(SendResult.FAILURE);
            }
        } else if (state == CbState.HALF_OPEN) {
            return CompletableFuture.completedFuture(SendResult.FAILURE);
        }

        // tryGzip 실패 시 null 반환 → Content-Encoding 헤더 미설정으로 디코딩 오류 방지
        byte[] compressed = gzipEnabled ? tryGzip(protoBody) : null;
        byte[] body = compressed != null ? compressed : protoBody;
        boolean isGzipped = compressed != null;

        CompletableFuture<SendResult> resultFuture = new CompletableFuture<>();
        doAttempt(path, body, isGzipped, 0, resultFuture);
        return resultFuture;
    }

    private void doAttempt(String path, byte[] body, boolean isGzipped, int attempt, CompletableFuture<SendResult> resultFuture) {
        if (isShutdown.get()) { resultFuture.complete(SendResult.SHUTDOWN); return; }
        if (attempt >= MAX_ATTEMPTS) { onSendFailure(path); resultFuture.complete(SendResult.FAILURE); return; }
        long jitter = ThreadLocalRandom.current().nextLong(100L * (1L << attempt));
        executeAttempt(path, body, isGzipped, attempt, resultFuture, RETRY_BACKOFF_MS[attempt] + jitter);
    }

    private void executeAttempt(String path, byte[] body, boolean isGzipped, int attempt,
                                 CompletableFuture<SendResult> resultFuture, long delayMs) {
        retryScheduler.schedule(() -> {
            if (isShutdown.get()) { resultFuture.complete(SendResult.SHUTDOWN); return; }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseEndpoint + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (isGzipped) reqBuilder.header("Content-Encoding", "gzip");
            extraHeaders.forEach(reqBuilder::header);

            httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, ex) -> {
                        if (ex != null) {
                            recordRetry();
                            doAttempt(path, body, isGzipped, attempt + 1, resultFuture);
                        } else if (response.statusCode() == 200) {
                            onSendSuccess(path, body.length);
                            resultFuture.complete(SendResult.SUCCESS);
                        } else if (response.statusCode() == 429) {
                            // P5: Retry-After 헤더를 존중하여 rate-limit 재시도 지연
                            long retryAfterMs = parseRetryAfterMs(response);
                            recordRetry();
                            int next = attempt + 1;
                            if (next >= MAX_ATTEMPTS) {
                                onSendFailure(path);
                                resultFuture.complete(SendResult.FAILURE);
                            } else {
                                executeAttempt(path, body, isGzipped, next, resultFuture, retryAfterMs);
                            }
                        } else if (response.statusCode() >= 400 && response.statusCode() < 500) {
                            onSendFailure(path);
                            resultFuture.complete(SendResult.FAILURE);
                        } else {
                            recordRetry();
                            doAttempt(path, body, isGzipped, attempt + 1, resultFuture);
                        }
                    });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // P5: Retry-After 헤더 파싱 (초 단위 정수값만 지원)
    private long parseRetryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try { return Long.parseLong(v.trim()) * 1_000L; }
                    catch (NumberFormatException ignored) { return DEFAULT_RETRY_AFTER_MS; }
                })
                .orElse(DEFAULT_RETRY_AFTER_MS);
    }

    private void onSendSuccess(String path, int bytes) {
        cbFailures.set(0);
        cbState.set(CbState.CLOSED);
        recordCbMetric(CbState.CLOSED);
        // P6: sender-level 성공 메트릭
        recordCounter("javi.otlp.send.success", 1);
        recordCounter("javi.otlp.send.bytes", bytes);
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
        // P6: sender-level 실패 메트릭
        recordCounter("javi.otlp.send.failure", 1);
    }

    // P6: retry 발생 시 카운터 기록
    private void recordRetry() {
        recordCounter("javi.otlp.send.retry", 1);
    }

    private void scheduleCbDrain(long delayMs) {
        if (drainScheduled.compareAndSet(false, true)) {
            retryScheduler.schedule(this::drainCbPendingQueue, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void drainCbPendingQueue() {
        drainScheduled.set(false);
        if (isShutdown.get()) {
            PendingPayload p;
            while ((p = cbPendingQueue.poll()) != null) p.future.complete(SendResult.FAILURE);
            return;
        }
        cbState.compareAndSet(CbState.OPEN, CbState.HALF_OPEN);
        PendingPayload first = cbPendingQueue.poll();
        if (first == null) return;
        submitDirect(first);
        first.future.whenComplete((result, ex) -> {
            if (result == SendResult.SUCCESS) {
                PendingPayload p;
                while ((p = cbPendingQueue.poll()) != null) submitDirect(p);
            } else {
                // probe 실패 → CB 재열림, 남은 버퍼 즉시 드롭
                PendingPayload p;
                while ((p = cbPendingQueue.poll()) != null) p.future.complete(SendResult.FAILURE);
            }
        });
    }

    private void submitDirect(PendingPayload payload) {
        byte[] compressed = gzipEnabled ? tryGzip(payload.protoBody) : null;
        byte[] body = compressed != null ? compressed : payload.protoBody;
        boolean isGzipped = compressed != null;
        doAttempt(payload.path, body, isGzipped, 0, payload.future);
    }

    private void recordCbMetric(CbState state) {
        try {
            int val = state == CbState.CLOSED ? 0 : state == CbState.HALF_OPEN ? 1 : 2;
            Map<String, String> attrs = Collections.singletonMap("signal", signalType);
            MetricRegistry.get().gauge("javi.otlp.circuit_breaker.state", attrs).set(val);
        } catch (Exception ignored) {}
    }

    private void recordCounter(String name, long delta) {
        try {
            Map<String, String> attrs = Collections.singletonMap("signal", signalType);
            MetricRegistry.get().counter(name, attrs).add(delta);
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        isShutdown.set(true);
        PendingPayload p;
        while ((p = cbPendingQueue.poll()) != null) p.future.complete(SendResult.SHUTDOWN);
        retryScheduler.shutdownNow();
        httpExecutor.shutdownNow();
        try {
            if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                AgentLogger.warn("OtlpHttpProtobufSender: retryScheduler 종료 타임아웃");
            }
            if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                AgentLogger.warn("OtlpHttpProtobufSender: httpExecutor 종료 타임아웃");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    // null 반환은 압축 실패를 의미 — 호출자가 Content-Encoding 헤더 결정에 활용
    private byte[] tryGzip(byte[] data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
            gzos.write(data);
            gzos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpProtobufSender: gzip 압축 실패, 비압축으로 전송");
            return null;
        }
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
