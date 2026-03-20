package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.logs.AgentLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 정기적인 메트릭 데이터 처리에 최적화된 전용 프로세서.
 *
 * <p><b>Bug #8 수정 내용:</b>
 * <ul>
 *   <li>Worker 스레드가 {@code exporter.export()}를 동기 블로킹하지 않는다.
 *       {@code export()}는 {@link CompletableFuture}를 반환하며, Worker는 즉시 다음
 *       배치를 처리한다. 이로써 503 재시도 중 큐 overflow로 인한 데이터 손실을 방지한다.</li>
 *   <li>{@link Semaphore}로 동시 in-flight export 수를 {@code MAX_CONCURRENT_EXPORTS}로
 *       제한해 메모리 사용량을 바운드한다. permit 획득 타임아웃 시 배치를 큐에 재삽입한다.</li>
 * </ul>
 */
public final class MetricBatchProcessor {

    /**
     * 동시 in-flight export 허용 수.
     *
     * <p>503 재시도(최대 4회, backoff 0+1+5+10=16초) 중에도 신규 배치를 계속
     * 전송할 수 있도록 충분한 동시성을 허용한다.
     * 너무 크면 메모리/커넥션 낭비이므로 4로 제한한다.
     */
    private static final int MAX_CONCURRENT_EXPORTS = 4;

    private final BlockingQueue<MetricData>  queue;
    private final DataExporter<MetricData>   exporter;
    private final int                        maxBatchSize;
    private final long                       exportIntervalMs;
    private final Thread                     workerThread;
    private final AtomicBoolean              isStopped = new AtomicBoolean(false);

    /**
     * in-flight export 수를 제한하는 Semaphore.
     *
     * <p>Worker 스레드는 export를 시작하기 전에 permit을 획득하고,
     * CompletableFuture가 완료될 때(성공/실패 무관) permit을 반환한다.
     * permit이 모두 소진된 경우 Worker는 {@code exportIntervalMs} 동안 대기하며,
     * 대기 중에도 생산자는 큐에 데이터를 계속 삽입할 수 있다.
     */
    private final Semaphore exportPermits = new Semaphore(MAX_CONCURRENT_EXPORTS);

    // 관측성 카운터
    private final AtomicLong totalDropped       = new AtomicLong(0);
    private final AtomicLong permitWaitTimeouts = new AtomicLong(0);

    public MetricBatchProcessor(DataExporter<MetricData> exporter,
                                 int maxQueueSize,
                                 int maxBatchSize,
                                 long exportIntervalMs) {
        this.exporter         = exporter;
        this.maxBatchSize     = maxBatchSize;
        this.exportIntervalMs = exportIntervalMs;
        this.queue            = new ArrayBlockingQueue<>(maxQueueSize);

        this.workerThread = new Thread(new Worker(), "javi-metric-processor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();

        AgentLogger.info("[MetricProcessor] 시작 (Queue:" + maxQueueSize
                + " maxConcurrentExports:" + MAX_CONCURRENT_EXPORTS + ")");
    }

    public void offer(MetricData record) {
        if (isStopped.get()) return;

        if (!queue.offer(record)) {
            totalDropped.incrementAndGet();
            if (!com.agent.config.RemoteConfigHolder.get().isDropOnFull()) {
                AgentLogger.warn("[MetricProcessor] Backpressure: 큐가 가득 참 — 메트릭 데이터 폐기"
                        + " (size=" + queue.size() + " total_dropped=" + totalDropped.get() + ")");
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
            workerThread.join(5_000); // 비동기 flush 완료를 위해 여유 있게 대기
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return exporter.shutdown();
    }

    /**
     * 큐에 남은 데이터를 모두 동기적으로 flush한다.
     *
     * <p>shutdown 경로에서 호출되므로 블로킹 완료를 기다린다.
     */
    public void flush() {
        List<MetricData> batch = new ArrayList<>(maxBatchSize);
        while (queue.drainTo(batch, maxBatchSize) > 0) {
            try {
                CompletableFuture<Void> fut = exporter.export(new ArrayList<>(batch));
                fut.get(30, TimeUnit.SECONDS);
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
                    if (firstItem == null) continue;

                    batch.add(firstItem);
                    queue.drainTo(batch, maxBatchSize - 1);

                    // in-flight export가 MAX_CONCURRENT_EXPORTS를 초과하지 않도록 permit 획득.
                    // permit 획득 실패(타임아웃) 시 배치를 큐에 재삽입하고 다음 루프로 넘어간다.
                    if (!exportPermits.tryAcquire(exportIntervalMs, TimeUnit.MILLISECONDS)) {
                        permitWaitTimeouts.incrementAndGet();
                        AgentLogger.warn("[MetricProcessor] export permit 획득 타임아웃"
                                + " — in-flight=" + (MAX_CONCURRENT_EXPORTS - exportPermits.availablePermits())
                                + " permit_timeouts=" + permitWaitTimeouts.get());
                        requeue(batch);
                        batch.clear();
                        continue;
                    }

                    // permit 획득 성공 — 비동기 export 시작.
                    // Worker 스레드는 즉시 반환되어 다음 배치를 처리한다.
                    final List<MetricData> exportBatch = new ArrayList<>(batch);
                    batch.clear();

                    exporter.export(exportBatch)
                            .whenComplete((v, ex) -> {
                                // 성공/실패 무관하게 항상 permit 반환
                                exportPermits.release();
                                if (ex != null) {
                                    AgentLogger.error("[MetricProcessor] 비동기 export 예외: "
                                            + ex.getMessage());
                                }
                            });

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    AgentLogger.error("[MetricProcessor] Worker 예외: " + t.getMessage());
                    batch.clear();
                }
            }
        }

        /**
         * permit 획득 타임아웃 시 배치 데이터를 다시 큐에 삽입한다.
         * 큐가 가득 차서 삽입 실패한 항목은 totalDropped에 기록된다.
         */
        private void requeue(List<MetricData> batch) {
            int requeued = 0;
            for (MetricData item : batch) {
                if (queue.offer(item)) {
                    requeued++;
                } else {
                    totalDropped.incrementAndGet();
                }
            }
            if (requeued < batch.size()) {
                AgentLogger.warn("[MetricProcessor] requeue: " + requeued + "/" + batch.size()
                        + " 항목 재삽입 (나머지 폐기)");
            }
        }
    }
}
