package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.metric.Counter;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 
 * Span 처리에 특화된 최적화된 Batch 프로세서.
 * 중간 추상화 레이어(BatchDataProcessor)를 제거하여 성능을 최적화했습니다.
 */
public final class BatchSpanProcessor implements SpanProcessor {

    private final BlockingQueue<Span> queue;
    private final SpanExporter exporter;
    private final int maxBatchSize;
    private final int maxQueueSize;
    private final long exportIntervalMs;
    private final Thread workerThread;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong droppedSpans  = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong exportedSpans = new java.util.concurrent.atomic.AtomicLong(0);
    /** forceFlush() 요청을 Worker에 전달하기 위한 신호 큐. */
    private final LinkedBlockingQueue<CountDownLatch> flushRequests = new LinkedBlockingQueue<>();

    // ---- Backpressure 메트릭 ----
    private final Gauge   queueSizeGauge;
    private final Gauge   queueUtilizationGauge;
    private final Counter droppedCounter;
    private final Counter exportedCounter;

    public BatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        this.exporter      = exporter;
        this.maxBatchSize  = maxBatchSize;
        this.maxQueueSize  = maxQueueSize;
        this.exportIntervalMs = delayMillis;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);

        // MetricRegistry에 backpressure 메트릭 등록
        MetricRegistry reg = MetricRegistry.get();
        this.queueSizeGauge        = reg.gauge("javi.pipeline.queue.size",        Collections.emptyMap());
        this.queueUtilizationGauge = reg.gauge("javi.pipeline.queue.utilization", Collections.emptyMap());
        this.droppedCounter        = reg.counter("javi.pipeline.spans.dropped",   Collections.emptyMap());
        this.exportedCounter       = reg.counter("javi.pipeline.spans.exported",  Collections.emptyMap());

        // 큐 최대 용량은 상수 — Gauge로 한 번 기록
        reg.gauge("javi.pipeline.queue.capacity", Collections.emptyMap()).set(maxQueueSize);

        this.workerThread = new Thread(new Worker(), "javi-span-processor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        AgentLogger.info("[BatchSpanProcessor] 시작 (Queue:" + maxQueueSize + ")");
    }

    @Override
    public void onStart(Span span) {
        // no-op
    }

    @Override
    public void onEnd(Span span) {
        if (span == null || isShutdown.get()) {
            return;
        }

        // 긴급 차단 — emergencyOff 시 모든 스팬 드롭
        if (com.agent.config.RemoteConfigHolder.get().isEmergencyOff()) {
            return;
        }

        // head sampler(ParentBased)가 SAMPLE 결정한 스팬만 export
        if (span.getContext().getTraceFlags().isSampled()) {
            offerToQueue(span);
        }
    }

    private void offerToQueue(Span span) {
        boolean offered;
        if (com.agent.config.RemoteConfigHolder.get().isDropOnFull()) {
            // 드롭 정책: 큐가 꽉 차면 즉시 드롭 (기본값)
            offered = queue.offer(span);
        } else {
            // 블로킹 정책: 큐에 공간이 생길 때까지 대기 (최대 100ms)
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
        // 큐 상태 메트릭 업데이트
        int qs = queue.size();
        queueSizeGauge.set(qs);
        queueUtilizationGauge.set(maxQueueSize > 0 ? qs * 100L / maxQueueSize : 0);
    }

    /**
     * Worker 스레드에 flush 신호를 보내고, Worker가 완료할 때까지 대기한다.
     * 직접 큐를 드레인하지 않으므로 Worker와의 레이스 컨디션이 없다.
     */
    @Override
    public CompletableResultCode forceFlush() {
        if (isShutdown.get()) {
            return CompletableResultCode.ofSuccess();
        }
        CountDownLatch latch = new CountDownLatch(1);
        flushRequests.offer(latch);
        workerThread.interrupt(); // blocking poll에서 깨운다
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        // Worker가 마지막 flush를 완료하도록 신호 후 중단
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
     * 현재 큐에 쌓인 모든 스팬을 즉시 내보낸다.
     * Worker 스레드에서만 호출해야 한다 (forceFlush 신호 처리 시 포함).
     * 오류 발생 시에도 나머지 배치 처리를 계속한다.
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
        @Override
        public void run() {
            List<Span> batch = new ArrayList<>(maxBatchSize);
            while (!isShutdown.get()) {
                try {
                    // forceFlush() 신호 처리 — interrupt로 깨어난 경우에도 확인
                    drainFlushRequests(batch);

                    Span firstItem = queue.poll(exportIntervalMs, TimeUnit.MILLISECONDS);
                    if (firstItem != null) {
                        batch.add(firstItem);
                        int extra = queue.drainTo(batch, maxBatchSize - 1);
                        int batchSize = 1 + extra;

                        try {
                            exporter.export(batch);
                            exportedSpans.addAndGet(batchSize);
                            exportedCounter.add(batchSize);
                        } catch (Throwable t) {
                            AgentLogger.error("[BatchSpanProcessor] Export error: " + t.getMessage());
                            droppedSpans.addAndGet(batchSize);
                            droppedCounter.add(batchSize);
                        } finally {
                            batch.clear();
                            int qs = queue.size();
                            queueSizeGauge.set(qs);
                            queueUtilizationGauge.set(maxQueueSize > 0 ? qs * 100L / maxQueueSize : 0);
                        }
                    }
                } catch (InterruptedException e) {
                    // forceFlush() 신호로 깨어난 경우 처리 후 루프 계속
                    drainFlushRequests(batch);
                    if (isShutdown.get()) {
                        break;
                    }
                    // isShutdown이 아직 false면 루프 유지 (interrupt 플래그 클리어됨)
                } catch (Throwable t) {
                    AgentLogger.error("[BatchSpanProcessor] Worker error: " + t.getMessage());
                    batch.clear();
                }
            }
        }

        /** 대기 중인 모든 flushRequest를 처리하고 latch를 카운트다운한다. */
        private void drainFlushRequests(List<Span> batch) {
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
