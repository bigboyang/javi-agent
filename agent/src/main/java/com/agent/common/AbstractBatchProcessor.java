package com.agent.common;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.metric.Counter;
import com.agent.metric.ExplicitBucketHistogram;
import com.agent.metric.Gauge;
import com.agent.metric.MetricRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Span / Log / Metric 배치 프로세서 공통 추상 기반 클래스.
 *
 * <ul>
 *   <li>Semaphore 기반 동시 in-flight export 제한 (MAX_CONCURRENT_EXPORTS = 4)</li>
 *   <li>CountDownLatch 기반 forceFlush() — 모든 신호 유형에서 동일하게 동작</li>
 *   <li>비동기 export (doExport) — Worker 스레드가 전송 완료를 기다리지 않음</li>
 *   <li>6종 self-telemetry 메트릭 — signal 태그로 traces/logs/metrics 구분</li>
 * </ul>
 *
 * <p>서브클래스는 {@link #doExport}, {@link #doShutdownExporter}, {@link #processorName}만
 * 구현하면 되며, {@link #startWorker()}를 생성자 마지막에 호출해 Worker를 시작한다.
 */
public abstract class AbstractBatchProcessor<T> {

    private static final int  MAX_CONCURRENT_EXPORTS = 4;
    private static final long FLUSH_TIMEOUT_S        = 5L;
    private static final long WORKER_JOIN_MS         = 2_000L;
    private static final long FLUSH_EXPORT_TIMEOUT_S = 30L;

    protected final int  maxBatchSize;
    protected final int  maxQueueSize;
    protected final long exportIntervalMs;

    private final BlockingQueue<T>                    queue;
    private final Thread                              workerThread;
    private final AtomicBoolean                       isShutdown     = new AtomicBoolean(false);
    private final AtomicLong                          droppedCount   = new AtomicLong(0);
    private final AtomicLong                          exportedCount  = new AtomicLong(0);
    private final AtomicLong                          permitTimeouts = new AtomicLong(0);
    private final LinkedBlockingQueue<CountDownLatch> flushRequests  = new LinkedBlockingQueue<>();
    private final Semaphore                           exportPermits  = new Semaphore(MAX_CONCURRENT_EXPORTS);

    private final Gauge                   queueSizeGauge;
    private final Gauge                   queueUtilizationGauge;
    private final Counter                 droppedCounter;
    private final Counter                 exportedCounter;
    private final Counter                 permitTimeoutCounter;
    private final ExplicitBucketHistogram exportLatencyHistogram;

    protected AbstractBatchProcessor(int maxQueueSize, int maxBatchSize, long exportIntervalMs,
                                      String signalType) {
        this.maxQueueSize     = maxQueueSize;
        this.maxBatchSize     = maxBatchSize;
        this.exportIntervalMs = exportIntervalMs;
        this.queue            = new ArrayBlockingQueue<>(maxQueueSize);

        Map<String, String> attrs = Collections.singletonMap("signal", signalType);
        MetricRegistry reg = MetricRegistry.get();
        this.queueSizeGauge         = reg.gauge("javi.pipeline.queue.size",               attrs);
        this.queueUtilizationGauge  = reg.gauge("javi.pipeline.queue.utilization",        attrs);
        this.droppedCounter         = reg.counter("javi.pipeline.dropped",                attrs);
        this.exportedCounter        = reg.counter("javi.pipeline.exported",               attrs);
        this.permitTimeoutCounter   = reg.counter("javi.pipeline.export.permit_timeouts", attrs);
        this.exportLatencyHistogram = reg.histogram("javi.pipeline.export.latency", "", "ms", attrs);
        reg.gauge("javi.pipeline.queue.capacity", attrs).set(maxQueueSize);

        this.workerThread = new Thread(new Worker(), "javi-" + signalType + "-processor");
        this.workerThread.setDaemon(true);
    }

    /**
     * Worker 스레드를 시작한다.
     * 서브클래스 생성자의 마지막 줄에서 반드시 호출해야 한다.
     * super() 완료 후 doExport() 오버라이드가 보장된 시점에 시작하도록 분리하였다.
     */
    protected final void startWorker() {
        workerThread.start();
    }

    // ── 서브클래스 계약 ────────────────────────────────────────────────────────

    /**
     * 배치를 비동기적으로 내보낸다.
     * @return true = 성공, false = 실패 (예외 발생 시에도 실패로 처리)
     */
    protected abstract CompletableFuture<Boolean> doExport(List<T> batch);

    /** 보유 중인 exporter를 종료한다. */
    protected abstract void doShutdownExporter();

    /** 로그에 쓰일 프로세서 이름 (예: "BatchSpanProcessor"). */
    protected abstract String processorName();

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /**
     * 항목을 큐에 삽입한다.
     * 큐가 가득 찬 경우 RemoteConfig.isDropOnFull() 에 따라 즉시 폐기하거나 100ms 대기한다.
     */
    public final void offerItem(T item) {
        if (isShutdown.get()) return;

        boolean offered;
        if (com.agent.config.RemoteConfigHolder.get().isDropOnFull()) {
            offered = queue.offer(item);
        } else {
            try {
                offered = queue.offer(item, 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                offered = false;
            }
        }

        if (!offered) {
            long total = droppedCount.incrementAndGet();
            droppedCounter.increment();
            AgentLogger.debug("[" + processorName() + "] Queue full, dropping item. total_dropped=" + total);
        }
        updateQueueMetrics();
    }

    /** 현재 큐를 즉시 내보내고 완료를 대기한다. */
    public final CompletableResultCode forceFlush() {
        if (isShutdown.get()) return CompletableResultCode.ofSuccess();
        CountDownLatch latch = new CountDownLatch(1);
        flushRequests.offer(latch);
        workerThread.interrupt();
        try {
            latch.await(FLUSH_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return CompletableResultCode.ofSuccess();
    }

    /**
     * 큐를 flush한 뒤 Worker를 종료하고 exporter를 닫는다.
     * Log/Metric 프로세서는 이 메서드를 직접 shutdown()으로 노출한다.
     */
    public final CompletableFuture<Void> shutdownAsync() {
        if (!isShutdown.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CountDownLatch latch = new CountDownLatch(1);
        flushRequests.offer(latch);
        workerThread.interrupt();
        try {
            latch.await(FLUSH_TIMEOUT_S, TimeUnit.SECONDS);
            workerThread.join(WORKER_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AgentLogger.info("[" + processorName() + "] shutdown: exported=" + exportedCount.get()
                + " dropped=" + droppedCount.get());
        doShutdownExporter();
        return CompletableFuture.completedFuture(null);
    }

    public final boolean isShutdown() {
        return isShutdown.get();
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private void flush() {
        List<T> batch = new ArrayList<>(maxBatchSize);
        int drained;
        while ((drained = queue.drainTo(batch, maxBatchSize)) > 0) {
            final int count = drained;
            try {
                Boolean ok = doExport(new ArrayList<>(batch)).get(FLUSH_EXPORT_TIMEOUT_S, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(ok)) {
                    exportedCount.addAndGet(count);
                    exportedCounter.add(count);
                } else {
                    droppedCount.addAndGet(count);
                    droppedCounter.add(count);
                }
            } catch (Throwable t) {
                AgentLogger.error("[" + processorName() + "] Flush error: " + t.getMessage());
                droppedCount.addAndGet(count);
                droppedCounter.add(count);
            } finally {
                batch.clear();
            }
        }
    }

    private void updateQueueMetrics() {
        int qs = queue.size();
        queueSizeGauge.set(qs);
        queueUtilizationGauge.set(maxQueueSize > 0 ? qs * 100L / maxQueueSize : 0);
    }

    private final class Worker implements Runnable {
        private final List<T> reusableBatch = new ArrayList<>(maxBatchSize);

        @Override
        public void run() {
            while (!isShutdown.get()) {
                try {
                    drainFlushRequests();

                    // 배치가 꽉 찼으면 delay 없이 즉시 처리
                    long effectiveDelay = queue.size() >= maxBatchSize ? 0L : exportIntervalMs;

                    T firstItem = queue.poll(effectiveDelay, TimeUnit.MILLISECONDS);
                    if (firstItem != null) {
                        reusableBatch.add(firstItem);
                        queue.drainTo(reusableBatch, maxBatchSize - 1);
                    } else if (!queue.isEmpty()) {
                        queue.drainTo(reusableBatch, maxBatchSize);
                    }

                    if (reusableBatch.isEmpty()) continue;

                    if (!exportPermits.tryAcquire(exportIntervalMs, TimeUnit.MILLISECONDS)) {
                        long timeouts = permitTimeouts.incrementAndGet();
                        permitTimeoutCounter.increment();
                        AgentLogger.warn("[" + processorName() + "] export permit 획득 타임아웃"
                                + " — in-flight=" + (MAX_CONCURRENT_EXPORTS - exportPermits.availablePermits())
                                + " permit_timeouts=" + timeouts);
                        requeueOrDrop(reusableBatch);
                        reusableBatch.clear();
                        continue;
                    }

                    final List<T> exportBatch = new ArrayList<>(reusableBatch);
                    final int     batchSize   = exportBatch.size();
                    final long    startMs     = System.currentTimeMillis();
                    reusableBatch.clear();

                    doExport(exportBatch).whenComplete((ok, ex) -> {
                        exportPermits.release();
                        if (ex == null && Boolean.TRUE.equals(ok)) {
                            exportedCount.addAndGet(batchSize);
                            exportedCounter.add(batchSize);
                            exportLatencyHistogram.record(System.currentTimeMillis() - startMs);
                        } else {
                            droppedCount.addAndGet(batchSize);
                            droppedCounter.add(batchSize);
                            if (ex != null) {
                                AgentLogger.warn("[" + processorName() + "] export 예외: " + ex.getMessage());
                            } else {
                                AgentLogger.warn("[" + processorName() + "] export 실패: batchSize=" + batchSize);
                            }
                        }
                        updateQueueMetrics();
                    });

                    updateQueueMetrics();

                } catch (InterruptedException e) {
                    // tryAcquire/poll 중단 전 reusableBatch에 수집된 항목을 먼저 큐에 돌려놓아
                    // 후속 flush()가 해당 항목을 처리할 수 있도록 한다.
                    requeueOrDrop(reusableBatch);
                    reusableBatch.clear();
                    drainFlushRequests();
                    if (isShutdown.get()) break;
                } catch (Throwable t) {
                    AgentLogger.error("[" + processorName() + "] Worker error: " + t.getMessage());
                    reusableBatch.clear();
                }
            }
            // isShutdown=true로 while 조건이 false가 되어 루프를 빠져나온 경우에도
            // 이미 등록된 flush 요청(shutdown latch 포함)을 처리해야 한다.
            drainFlushRequests();
        }

        private void requeueOrDrop(List<T> batch) {
            int requeued = 0;
            for (T item : batch) {
                if (queue.offer(item)) {
                    requeued++;
                } else {
                    droppedCount.incrementAndGet();
                    droppedCounter.increment();
                }
            }
            if (requeued < batch.size()) {
                AgentLogger.warn("[" + processorName() + "] requeue: " + requeued + "/" + batch.size()
                        + " 항목 재삽입 (나머지 폐기)");
            }
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
