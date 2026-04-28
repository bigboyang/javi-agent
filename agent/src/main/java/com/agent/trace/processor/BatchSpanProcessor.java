package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.metric.Counter;
import com.agent.metric.ExplicitBucketHistogram;
import com.agent.metric.Gauge;
import com.agent.metric.MetricRegistry;
import com.agent.span.Span;
import com.agent.trace.exporter.SpanExporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Span 처리에 특화된 최적화된 Batch 프로세서.
 * 중간 추상화 레이어(BatchDataProcessor)를 제거하여 성능을 최적화했습니다.
 */
public final class BatchSpanProcessor implements SpanProcessor {

    // P1: 동시 in-flight export 허용 수 — Semaphore로 바운드
    private static final int MAX_CONCURRENT_EXPORTS = 4;

    private final BlockingQueue<Span> queue;
    private final SpanExporter exporter;
    private final int maxBatchSize;
    private final int maxQueueSize;
    private final long exportIntervalMs;
    private final Thread workerThread;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicLong droppedSpans  = new AtomicLong(0);
    private final AtomicLong exportedSpans = new AtomicLong(0);
    private final AtomicLong permitTimeouts = new AtomicLong(0);
    /** forceFlush() 요청을 Worker에 전달하기 위한 신호 큐. */
    private final LinkedBlockingQueue<CountDownLatch> flushRequests = new LinkedBlockingQueue<>();

    // P1: 동시 export 수를 제한하는 Semaphore
    private final Semaphore exportPermits = new Semaphore(MAX_CONCURRENT_EXPORTS);

    // ---- Backpressure 메트릭 ----
    private final Gauge                   queueSizeGauge;
    private final Gauge                   queueUtilizationGauge;
    private final Counter                 droppedCounter;
    private final Counter                 exportedCounter;
    private final Counter                 permitTimeoutCounter;
    private final ExplicitBucketHistogram exportLatencyHistogram;

    public BatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        this.exporter      = exporter;
        this.maxBatchSize  = maxBatchSize;
        this.maxQueueSize  = maxQueueSize;
        this.exportIntervalMs = delayMillis;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);

        MetricRegistry reg = MetricRegistry.get();
        this.queueSizeGauge        = reg.gauge("javi.pipeline.queue.size",               Collections.emptyMap());
        this.queueUtilizationGauge = reg.gauge("javi.pipeline.queue.utilization",        Collections.emptyMap());
        this.droppedCounter        = reg.counter("javi.pipeline.spans.dropped",          Collections.emptyMap());
        this.exportedCounter       = reg.counter("javi.pipeline.spans.exported",         Collections.emptyMap());
        this.permitTimeoutCounter  = reg.counter("javi.pipeline.export.permit_timeouts", Collections.emptyMap());
        this.exportLatencyHistogram = reg.histogram("javi.pipeline.export.latency", "", "ms", Collections.emptyMap());

        reg.gauge("javi.pipeline.queue.capacity", Collections.emptyMap()).set(maxQueueSize);

        this.workerThread = new Thread(new Worker(), "javi-span-processor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        AgentLogger.info("[BatchSpanProcessor] 시작 (Queue:" + maxQueueSize
                + " maxConcurrentExports:" + MAX_CONCURRENT_EXPORTS + ")");
    }

    @Override
    public void onStart(Span span) {
        // no-op
    }

    @Override
    public void onEnd(Span span) {
        if (span == null || isShutdown.get()) return;

        if (com.agent.config.RemoteConfigHolder.get().isEmergencyOff()) return;

        if (span.getContext().getTraceFlags().isSampled()) {
            offerToQueue(span);
        }
    }

    private void offerToQueue(Span span) {
        boolean offered;
        if (com.agent.config.RemoteConfigHolder.get().isDropOnFull()) {
            offered = queue.offer(span);
        } else {
            try {
                offered = queue.offer(span, 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                offered = false;
            }
        }

        if (!offered) {
            long dropped = droppedSpans.incrementAndGet();
            droppedCounter.increment();
            AgentLogger.debug("[BatchSpanProcessor] Queue full, dropping span. total_dropped=" + dropped);
        }
        int qs = queue.size();
        queueSizeGauge.set(qs);
        queueUtilizationGauge.set(maxQueueSize > 0 ? qs * 100L / maxQueueSize : 0);
    }

    @Override
    public CompletableResultCode forceFlush() {
        if (isShutdown.get()) return CompletableResultCode.ofSuccess();
        CountDownLatch latch = new CountDownLatch(1);
        flushRequests.offer(latch);
        workerThread.interrupt();
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return CompletableResultCode.ofSuccess();
        CountDownLatch latch = new CountDownLatch(1);
        flushRequests.offer(latch);
        workerThread.interrupt();
        try {
            latch.await(3, TimeUnit.SECONDS);
            workerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AgentLogger.info("[BatchSpanProcessor] shutdown: exported=" + exportedSpans.get()
                + " dropped=" + droppedSpans.get());
        exporter.shutdown();
        return CompletableResultCode.ofSuccess();
    }

    /**
     * 큐에 쌓인 스팬을 동기적으로 전송한다. forceFlush/shutdown 경로에서만 호출.
     */
    private void flush() {
        List<Span> batch = new ArrayList<>(maxBatchSize);
        int drained;
        while ((drained = queue.drainTo(batch, maxBatchSize)) > 0) {
            try {
                exporter.export(batch);
                exportedSpans.addAndGet(drained);
                exportedCounter.add(drained);
            } catch (Throwable t) {
                AgentLogger.error("[BatchSpanProcessor] Flush error: " + t.getMessage());
                droppedSpans.addAndGet(drained);
                droppedCounter.add(drained);
            } finally {
                batch.clear();
            }
        }
    }

    private final class Worker implements Runnable {
        private final List<Span> reusableBatch = new ArrayList<>(maxBatchSize);

        @Override
        public void run() {
            while (!isShutdown.get()) {
                try {
                    drainFlushRequests();

                    // P4 fix: 배치가 꽉 찼을 때 즉시 처리 (이전: 70% 기준이 maxQueueSize*0.7로 잘못 계산됨)
                    long effectiveDelay = queue.size() >= maxBatchSize ? 0 : exportIntervalMs;

                    Span firstItem = queue.poll(effectiveDelay, TimeUnit.MILLISECONDS);
                    if (firstItem != null) {
                        reusableBatch.add(firstItem);
                        queue.drainTo(reusableBatch, maxBatchSize - 1);
                    } else if (!queue.isEmpty()) {
                        queue.drainTo(reusableBatch, maxBatchSize);
                    }

                    if (reusableBatch.isEmpty()) continue;

                    // P1 fix: permit 획득 → 비동기 export → whenComplete에서 permit 반환
                    // Worker 스레드가 export 완료를 기다리지 않아 블로킹 없이 다음 배치를 처리한다.
                    if (!exportPermits.tryAcquire(exportIntervalMs, TimeUnit.MILLISECONDS)) {
                        long timeouts = permitTimeouts.incrementAndGet();
                        permitTimeoutCounter.increment();
                        AgentLogger.warn("[BatchSpanProcessor] export permit 획득 타임아웃"
                                + " — in-flight=" + (MAX_CONCURRENT_EXPORTS - exportPermits.availablePermits())
                                + " permit_timeouts=" + timeouts);
                        requeueOrDrop(reusableBatch);
                        reusableBatch.clear();
                        continue;
                    }

                    final List<Span> exportBatch = new ArrayList<>(reusableBatch);
                    final int batchSize = exportBatch.size();
                    final long startMs = System.currentTimeMillis();
                    reusableBatch.clear();

                    exporter.exportAsync(exportBatch)
                            .whenComplete((result, ex) -> {
                                exportPermits.release();
                                if (ex != null || result == null || !result.isSuccess()) {
                                    droppedSpans.addAndGet(batchSize);
                                    droppedCounter.add(batchSize);
                                    AgentLogger.warn("[BatchSpanProcessor] export 실패: batchSize=" + batchSize);
                                } else {
                                    exportedSpans.addAndGet(batchSize);
                                    exportedCounter.add(batchSize);
                                    exportLatencyHistogram.record(System.currentTimeMillis() - startMs);
                                }
                                int qs = queue.size();
                                queueSizeGauge.set(qs);
                                queueUtilizationGauge.set(maxQueueSize > 0 ? qs * 100L / maxQueueSize : 0);
                            });

                    updateMetrics();
                } catch (InterruptedException e) {
                    drainFlushRequests();
                    if (isShutdown.get()) break;
                } catch (Throwable t) {
                    AgentLogger.error("[BatchSpanProcessor] Worker error: " + t.getMessage());
                    reusableBatch.clear();
                }
            }
        }

        private void requeueOrDrop(List<Span> batch) {
            for (Span span : batch) {
                if (!queue.offer(span)) {
                    droppedSpans.incrementAndGet();
                    droppedCounter.increment();
                }
            }
        }

        private void updateMetrics() {
            int qs = queue.size();
            queueSizeGauge.set(qs);
            queueUtilizationGauge.set(maxQueueSize > 0 ? qs * 100L / maxQueueSize : 0);
        }

        private void drainFlushRequests() {
            CountDownLatch latch;
            while ((latch = flushRequests.poll()) != null) {
                try {
                    flush();
                } finally {
                    latch.countDown();
                }
            }
        }
    }
}
