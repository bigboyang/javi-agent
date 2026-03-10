package com.agent.logs;

import com.agent.common.DataExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 고빈도 로그 데이터 처리에 최적화된 전용 프로세서.
 */
public final class LogBatchProcessor {

    private final BlockingQueue<LogRecord> queue;
    private final DataExporter<LogRecord> exporter;
    private final int maxBatchSize;
    private final long exportIntervalMs;
    private final Thread workerThread;
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    public LogBatchProcessor(DataExporter<LogRecord> exporter, int maxQueueSize, int maxBatchSize, long exportIntervalMs) {
        this.exporter = exporter;
        this.maxBatchSize = maxBatchSize;
        this.exportIntervalMs = exportIntervalMs;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);

        this.workerThread = new Thread(new Worker(), "javi-log-processor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        AgentLogger.info("[LogProcessor] 시작 (Queue:" + maxQueueSize + ")");
    }

    public void offer(LogRecord record) {
        if (isStopped.get()) return;
        
        // Backpressure: 큐 임계치 초과 시 폐기 (Drop-newest 전략)
        if (!queue.offer(record)) {
            // 원격 설정에서 dropOnFull이 true이면 경고 없이 버리고, false면 로그를 남김 (거버넌스 연동)
            if (!com.agent.config.RemoteConfigHolder.get().isDropOnFull()) {
                AgentLogger.warn("[LogProcessor] Backpressure: 큐 임계치 초과 — 로그 데이터 폐기 (size=" + queue.size() + ")");
            }
        }
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
        List<LogRecord> batch = new ArrayList<>(maxBatchSize);
        while (queue.drainTo(batch, maxBatchSize) > 0) {
            try {
                exporter.export(new ArrayList<>(batch));
                batch.clear();
            } catch (Throwable t) {
                AgentLogger.error("[LogProcessor] Flush error: " + t.getMessage());
                break;
            }
        }
    }

    private final class Worker implements Runnable {
        @Override
        public void run() {
            List<LogRecord> batch = new ArrayList<>(maxBatchSize);
            while (!isStopped.get()) {
                try {
                    LogRecord firstItem = queue.poll(exportIntervalMs, TimeUnit.MILLISECONDS);
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
                    AgentLogger.error("[LogProcessor] Worker error: " + t.getMessage());
                }
            }
        }
    }
}
