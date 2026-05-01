package com.agent.trace.processor;

import com.agent.common.AbstractBatchProcessor;
import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.span.Span;
import com.agent.trace.exporter.SpanExporter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Span 전용 배치 프로세서. {@link AbstractBatchProcessor}에서 공통 로직을 상속받는다.
 */
public final class BatchSpanProcessor extends AbstractBatchProcessor<Span> implements SpanProcessor {

    private final SpanExporter exporter;

    public BatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        super(maxQueueSize, maxBatchSize, delayMillis, "traces");
        this.exporter = exporter;
        startWorker();
        AgentLogger.info("[BatchSpanProcessor] 시작 (Queue:" + maxQueueSize + " maxConcurrentExports:4)");
    }

    @Override
    protected CompletableFuture<Boolean> doExport(List<Span> batch) {
        return exporter.exportAsync(batch).thenApply(r -> r != null && r.isSuccess());
    }

    @Override
    protected void doShutdownExporter() {
        exporter.shutdown();
    }

    @Override
    protected String processorName() {
        return "BatchSpanProcessor";
    }

    @Override
    public void onStart(Span span) {}

    @Override
    public void onEnd(Span span) {
        if (span == null || isShutdown()) return;
        if (com.agent.config.RemoteConfigHolder.get().isEmergencyOff()) return;
        if (span.getContext().getTraceFlags().isSampled()) {
            offerItem(span);
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        shutdownAsync();
        return CompletableResultCode.ofSuccess();
    }
}
