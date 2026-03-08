package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.logs.AgentLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 정기적인 메트릭 데이터 처리에 최적화된 전용 프로세서.
 */
public final class MetricBatchProcessor {

    private final BlockingQueue<MetricData> queue;
    private final DataExporter<MetricData> exporter;
    private final int maxBatchSize;
    private final long exportIntervalMs;
    private final Thread workerThread;
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    public MetricBatchProcessor(DataExporter<MetricData> exporter, int maxQueueSize, int maxBatchSize, long exportIntervalMs) {
        this.exporter = exporter;
        this.maxBatchSize = maxBatchSize;
        this.exportIntervalMs = exportIntervalMs;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);

        this.workerThread = new Thread(new Worker(), "javi-metric-processor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        AgentLogger.info("[MetricProcessor] 시작 (Queue:" + maxQueueSize + ")");
    }

    public void offer(MetricData record) {
        if (isStopped.get()) return;
        queue.offer(record);
    }

    public CompletableFuture<Void> shutdown() {
        if (!isStopped.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        workerThread.interrupt();
        try {
            flush();
            workerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return exporter.shutdown();
    }

    public void flush() {
        List<MetricData> batch = new ArrayList<>(maxBatchSize);
        while (queue.drainTo(batch, maxBatchSize) > 0) {
            try {
                exporter.export(new ArrayList<>(batch));
                batch.clear();
            } catch (Throwable t) {
                AgentLogger.error("[MetricProcessor] Flush error: " + t.getMessage());
                break;
            }
        }
    }

    private final class Worker implements Runnable {
        @Override
        public void run() {
            List<MetricData> batch = new ArrayList<>(maxBatchSize);
            while (!isStopped.get()) {
                try {
                    MetricData firstItem = queue.poll(exportIntervalMs, TimeUnit.MILLISECONDS);
                    if (firstItem != null) {
                        batch.add(firstItem);
                        queue.drainTo(batch, maxBatchSize - 1);
                        exporter.export(new ArrayList<>(batch));
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    AgentLogger.error("[MetricProcessor] Worker error: " + t.getMessage());
                }
            }
        }
    }
}
