package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import com.agent.trace.exporter.SpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Buffers spans and exports them in batches on a schedule. */
public final class BatchSpanProcessor implements SpanProcessor {
    private static final Logger LOGGER = Logger.getLogger(BatchSpanProcessor.class.getName());

    private final SpanExporter exporter;
    private final BlockingQueue<Span> queue;
    private final int maxBatchSize;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public BatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter");
        }
        if (maxQueueSize <= 0 || maxBatchSize <= 0 || maxBatchSize > maxQueueSize) {
            throw new IllegalArgumentException("invalid batch settings");
        }
        this.exporter = exporter;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);
        this.maxBatchSize = maxBatchSize;
        this.worker = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        this.worker.scheduleAtFixedRate(this::exportBatchSafely, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onStart(Span span) {
        // no-op
    }

    @Override
    public void onEnd(Span span) {
        if (span == null || shutdown.get()) {
            return;
        }
        if (!queue.offer(span)) {
            LOGGER.log(Level.FINE, "batch span processor drop: queue full");
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        CompletableResultCode result = new CompletableResultCode();
        if (shutdown.get()) {
            result.succeed();
            return result;
        }
        try {
            worker.execute(
                    () -> {
                        try {
                            exportAll();
                            result.succeed();
                        } catch (RuntimeException ex) {
                            LOGGER.log(Level.FINE, "forceFlush failed", ex);
                            result.fail();
                        }
                    });
        } catch (RuntimeException ex) {
            result.fail();
        }
        return result;
    }

    @Override
    public CompletableResultCode shutdown() {
        CompletableResultCode result = new CompletableResultCode();
        if (!shutdown.compareAndSet(false, true)) {
            result.succeed();
            return result;
        }
        try {
            worker.execute(
                    () -> {
                        try {
                            exportAll();
                            worker.shutdown();
                            result.succeed();
                        } catch (RuntimeException ex) {
                            LOGGER.log(Level.FINE, "shutdown failed", ex);
                            result.fail();
                        }
                    });
        } catch (RuntimeException ex) {
            result.fail();
        }
        return result;
    }

    private void exportBatchSafely() {
        try {
            exportBatch();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.FINE, "batch export failed", ex);
        }
    }

    private void exportBatch() {
        List<Span> batch = new ArrayList<>(maxBatchSize);
        queue.drainTo(batch, maxBatchSize);
        if (batch.isEmpty()) {
            return;
        }
        exporter.export(batch);
    }

    private void exportAll() {
        List<Span> batch = new ArrayList<>(maxBatchSize);
        while (true) {
            queue.drainTo(batch, maxBatchSize);
            if (batch.isEmpty()) {
                break;
            }
            exporter.export(batch);
            batch.clear();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "batch-span-processor");
            thread.setDaemon(true);
            return thread;
        }
    }
}
