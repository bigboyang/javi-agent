package com.agent.logs;

import com.agent.common.AbstractBatchProcessor;
import com.agent.common.DataExporter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Log 전용 배치 프로세서. {@link AbstractBatchProcessor}에서 공통 로직을 상속받는다.
 */
public final class LogBatchProcessor extends AbstractBatchProcessor<LogRecord> {

    private final DataExporter<LogRecord> exporter;

    public LogBatchProcessor(DataExporter<LogRecord> exporter,
                              int maxQueueSize, int maxBatchSize, long exportIntervalMs) {
        super(maxQueueSize, maxBatchSize, exportIntervalMs, "logs");
        this.exporter = exporter;
        startWorker();
        AgentLogger.info("[LogBatchProcessor] 시작 (Queue:" + maxQueueSize + " maxConcurrentExports:4)");
    }

    @Override
    protected CompletableFuture<Boolean> doExport(List<LogRecord> batch) {
        return exporter.export(batch).thenApply(v -> Boolean.TRUE);
    }

    @Override
    protected void doShutdownExporter() {
        exporter.shutdown();
    }

    @Override
    protected String processorName() {
        return "LogBatchProcessor";
    }

    public void offer(LogRecord record) {
        offerItem(record);
    }

    public CompletableFuture<Void> shutdown() {
        return shutdownAsync();
    }
}
