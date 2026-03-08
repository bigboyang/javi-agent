package com.agent.logs;


import com.agent.common.DataExporter;
import java.util.concurrent.CompletableFuture;

/**
 * 로그 수집의 엔트리 포인트 및 생명주기 관리자. (Datadog/OTel 스타일)
 */
public final class SdkLoggerProvider {
    private final LogBatchProcessor logProcessor;
    private volatile boolean isShutdown = false;

    public SdkLoggerProvider(DataExporter<LogRecord> exporter) {
        // 독립적인 배칭 프로세서와 워커 스레드 할당
        this.logProcessor = new LogBatchProcessor(
                exporter,
                2048,  // maxQueueSize
                512,   // maxBatchSize
                5000   // exportIntervalMs
        );
    }

    /**
     * 로그 발생 시 호출하여 파이프라인에 태운다.
     */
    public void emit(LogRecord record) {
        if (isShutdown) return;
        logProcessor.offer(record);
    }

    public CompletableFuture<Void> shutdown() {
        isShutdown = true;
        return logProcessor.shutdown();
    }
}
