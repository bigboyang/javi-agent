package com.agent.common.grpc;

import com.agent.logs.AgentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTLP/gRPC 전송을 담당하는 공통 Transport.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>Java 11 HttpClient with Version.HTTP_2 (h2c cleartext / TLS)</li>
 *   <li>grpc-java 의존성 없음 — gRPC 프레이밍을 수동 구현</li>
 *   <li>5-byte length-prefix 프레임: {@link ProtoEncoder#wrapGrpcFrame}</li>
 *   <li>지수 백오프 재시도: 5xx / 네트워크 오류에만 적용, 4xx는 즉시 실패</li>
 *   <li>HTTP/2 트레일러(grpc-status)는 Java 11 HttpClient가 노출하지 않으므로
 *       HTTP 200 = 성공으로 처리 (Collector는 수신 성공 시 항상 200 반환)</li>
 * </ul>
 *
 * <p>설정 환경변수:
 * <ul>
 *   <li>JAVI_GRPC_ENDPOINT / javi.grpc.endpoint (기본: http://localhost:4317)</li>
 *   <li>JAVI_COLLECTOR_TIMEOUT_MS / javi.collector.timeout.ms (기본: 10000)</li>
 * </ul>
 */
public final class GrpcSender {

    private static final String ENV_GRPC_ENDPOINT  = "JAVI_GRPC_ENDPOINT";
    private static final String PROP_GRPC_ENDPOINT = "javi.grpc.endpoint";
    private static final String DEFAULT_ENDPOINT   = "http://localhost:4317";

    /** 재시도 간격 (ms): attempt 0=즉시, 1=1s, 2=5s, 3=10s */
    private static final long[] RETRY_BACKOFF_MS = {0L, 1_000L, 5_000L, 10_000L};

    /** 최대 재시도 횟수 (첫 시도 포함) */
    private static final int MAX_ATTEMPTS = 4;

    public enum SendResult { SUCCESS, FAILURE, SHUTDOWN }

    private final String baseEndpoint;
    private final long   timeoutMs;
    private final HttpClient httpClient;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // ---- 생성자 ----

    private GrpcSender(String baseEndpoint, long timeoutMs) {
        this.baseEndpoint = baseEndpoint.replaceAll("/+$", "");
        this.timeoutMs    = timeoutMs;

        ThreadFactory daemon = r -> {
            Thread t = new Thread(r, "javi-grpc-sender");
            t.setDaemon(true);
            return t;
        };
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(2, daemon))
                .build();
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

        byte[] frame = ProtoEncoder.wrapGrpcFrame(protoBody);
        URI uri = URI.create(baseEndpoint + grpcPath);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleepUninterruptibly(RETRY_BACKOFF_MS[attempt])) {
                return SendResult.FAILURE;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("content-type", "application/grpc+proto")
                        .header("te", "trailers")
                        .header("grpc-encoding", "identity")
                        .header("user-agent", "grpc-java/javi-agent-1.0")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(frame))
                        .build();

                HttpResponse<Void> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.discarding());

                int status = response.statusCode();

                if (status == 200) {
                    AgentLogger.debug("gRPC send success path=" + grpcPath
                            + " bytes=" + protoBody.length);
                    return SendResult.SUCCESS;
                }

                if (status >= 400 && status < 500) {
                    AgentLogger.warn("gRPC send rejected (4xx=" + status + ") path=" + grpcPath
                            + " — 재시도 없음");
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

        AgentLogger.warn("gRPC send exhausted retries path=" + grpcPath);
        return SendResult.FAILURE;
    }

    public void shutdown() {
        isShutdown.set(true);
        AgentLogger.info("GrpcSender shutdown 완료");
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
