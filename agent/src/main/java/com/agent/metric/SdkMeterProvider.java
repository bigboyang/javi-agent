package com.agent.metric;


import com.agent.common.DataExporter;
import java.util.concurrent.CompletableFuture;

/**
 * 메트릭 수집의 엔트리 포인트 및 생명주기 관리자.
 */
public final class SdkMeterProvider {
    private final MetricBatchProcessor metricProcessor;
    private volatile boolean isShutdown = false;

    public SdkMeterProvider(DataExporter<MetricData> exporter) {
        // 독립적인 배칭 프로세서와 워커 스레드 할당
        this.metricProcessor = new MetricBatchProcessor(
                exporter,
                1024,  // maxQueueSize
                256,   // maxBatchSize
                15000  // exportIntervalMs (메트릭은 조금 더 길게 15초)
        );
    }

    /**
     * 메트릭 스냅샷 등을 큐에 넣는다.
     */
    public void record(MetricData data) {
        if (isShutdown) return;
        metricProcessor.offer(data);
    }

    public CompletableFuture<Void> shutdown() {
        isShutdown = true;
        return metricProcessor.shutdown();
    }
}
