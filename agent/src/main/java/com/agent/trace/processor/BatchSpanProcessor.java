package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.span.Span;
import com.agent.trace.exporter.SpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
    private final long exportIntervalMs;
    private final Thread workerThread;
    private final java.util.concurrent.ScheduledExecutorService samplerResetExecutor;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // Tail Sampling 설정 캐싱
    private final boolean tailSamplingEnabled;
    private final long slowThresholdNanos;
    private final java.util.Set<String> criticalUrls;

    // 클러스터링 기반 샘플링을 위한 카운터 (Pattern -> Count)
    private final int clusterMinSamples;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> clusterCounters = new java.util.concurrent.ConcurrentHashMap<>();

    public BatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        this.exporter = exporter;
        this.maxBatchSize = maxBatchSize;
        this.exportIntervalMs = delayMillis;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);

        com.agent.config.AgentConfig config = com.agent.config.AgentConfig.load();
        this.tailSamplingEnabled = config.isTailSamplingEnabled();
        this.slowThresholdNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(config.getSlowThresholdMs());
        this.criticalUrls = new java.util.HashSet<>(config.getCriticalUrls());
        this.clusterMinSamples = config.getClusterMinSamples();

        // 1분마다 클러스터 카운터 초기화 스케줄링 (주기적인 대표 샘플 확보용)
        this.samplerResetExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-sampler-reset");
            t.setDaemon(true);
            return t;
        });
        this.samplerResetExecutor.scheduleAtFixedRate(clusterCounters::clear, 1, 1, java.util.concurrent.TimeUnit.MINUTES);

        this.workerThread = new Thread(new Worker(), "javi-span-processor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        AgentLogger.info("[BatchSpanProcessor] 시작 (Queue:" + maxQueueSize + ", TailSampling:" + tailSamplingEnabled + ", ClusterMinSamples:" + clusterMinSamples + ")");
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

        boolean originallySampled = span.getContext().getTraceFlags().isSampled();

        // 1. Head Sampling에 의해 이미 선택된 경우 (가장 빈번한 경로)
        if (originallySampled) {
            offerToQueue(span);
            return;
        }

        // 2. Tail Sampling 로직 (누락된 트레이스 중 가치 있는 것 구조)
        if (tailSamplingEnabled && isHighValueSpan(span)) {
            AgentLogger.debug("[TailSampling] Rescued span: " + span.getName() + " (traceId=" + span.getContext().getTraceId() + ")");
            offerToQueue(span);
        }
    }

    private boolean isHighValueSpan(com.agent.span.Span span) {
        if (!(span instanceof com.agent.span.ReadableSpan)) {
            return false;
        }
        com.agent.span.ReadableSpan readableSpan = (com.agent.span.ReadableSpan) span;

        // 원격 설정에서 활성 tail policy 확인
        java.util.Set<String> policy = com.agent.config.RemoteConfigHolder.get().getTailPolicy();

        // 1. 에러 기반 (Error-based)
        if (policy.contains("error") && readableSpan.getStatus() == com.agent.span.SpanStatus.ERROR) {
            return true;
        }

        // 2. 대기시간 기반 (Latency-based)
        if (policy.contains("slow") && slowThresholdNanos > 0) {
            long duration = readableSpan.getEndTimeNanos() - readableSpan.getStartTimeNanos();
            if (duration >= slowThresholdNanos) {
                return true;
            }
        }

        // 3. 검색/속성 기반 (Search/Attribute-based)
        if (policy.contains("critical_url") && !criticalUrls.isEmpty()) {
            String name = readableSpan.getName();
            for (String url : criticalUrls) {
                if (name.contains(url)) {
                    return true;
                }
            }
        }

        // 4. 클러스터링 기반 (Clustering-based)
        if (policy.contains("cluster") && clusterMinSamples > 0) {
            String name = readableSpan.getName();
            java.util.concurrent.atomic.AtomicLong counter = clusterCounters.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong(0));
            if (counter.incrementAndGet() <= clusterMinSamples) {
                return true;
            }
        }

        return false;
    }

    private void offerToQueue(Span span) {
        if (com.agent.config.RemoteConfigHolder.get().isDropOnFull()) {
            // 드롭 정책: 큐가 꽉 차면 즉시 드롭 (기본값)
            if (!queue.offer(span)) {
                AgentLogger.debug("[BatchSpanProcessor] Queue full, dropping span.");
            }
        } else {
            // 블로킹 정책: 큐에 공간이 생길 때까지 대기 (최대 100ms)
            try {
                if (!queue.offer(span, 100, TimeUnit.MILLISECONDS)) {
                    AgentLogger.debug("[BatchSpanProcessor] Queue full after 100ms wait, dropping span.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        flush();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        samplerResetExecutor.shutdownNow();
        workerThread.interrupt();
        try {
            // 종료 전 잔여 데이터 전송
            flush();
            workerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exporter.shutdown();
        return CompletableResultCode.ofSuccess();
    }

    /**
     * 현재 큐에 쌓인 모든 스팬을 즉시 내보낸다.
     */
    private void flush() {
        List<Span> batch = new ArrayList<>(maxBatchSize);
        while (queue.drainTo(batch, maxBatchSize) > 0) {
            try {
                exporter.export(batch);
                batch.clear();
            } catch (Throwable t) {
                AgentLogger.error("[BatchSpanProcessor] Flush error: " + t.getMessage());
                break;
            }
        }
    }

    private final class Worker implements Runnable {
        @Override
        public void run() {
            List<Span> batch = new ArrayList<>(maxBatchSize);
            while (!isShutdown.get()) {
                try {
                    Span firstItem = queue.poll(exportIntervalMs, TimeUnit.MILLISECONDS);
                    if (firstItem != null) {
                        batch.add(firstItem);
                        queue.drainTo(batch, maxBatchSize - 1);
                        
                        exporter.export(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    AgentLogger.error("[BatchSpanProcessor] Worker error: " + t.getMessage());
                }
            }
        }
    }
}
