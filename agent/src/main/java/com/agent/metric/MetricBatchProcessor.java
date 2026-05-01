package com.agent.metric;

import com.agent.common.AbstractBatchProcessor;
import com.agent.common.DataExporter;
import com.agent.logs.AgentLogger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Metric 전용 배치 프로세서. {@link AbstractBatchProcessor}에서 공통 로직을 상속받는다.
 */
public final class MetricBatchProcessor extends AbstractBatchProcessor<MetricData> {

    private final DataExporter<MetricData> exporter;

    public MetricBatchProcessor(DataExporter<MetricData> exporter,
                                 int maxQueueSize, int maxBatchSize, long exportIntervalMs) {
        super(maxQueueSize, maxBatchSize, exportIntervalMs, "metrics");
        this.exporter = exporter;
        startWorker();
        AgentLogger.info("[MetricBatchProcessor] 시작 (Queue:" + maxQueueSize + " maxConcurrentExports:4)");
    }

    @Override
    protected CompletableFuture<Boolean> doExport(List<MetricData> batch) {
        return exporter.export(batch).thenApply(v -> Boolean.TRUE);
    }

    @Override
    protected void doShutdownExporter() {
        exporter.shutdown();
    }

    @Override
    protected String processorName() {
        return "MetricBatchProcessor";
    }

    public void offer(MetricData data) {
        offerItem(data);
    }

    public CompletableFuture<Void> shutdown() {
        return shutdownAsync();
    }
}
