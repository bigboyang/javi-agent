package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.config.AgentConfig;
import com.agent.logs.AgentLogger;
import com.agent.span.ReadableSpan;
import com.agent.span.Span;
import com.agent.span.SpanId;
import com.agent.span.SpanStatus;
import com.agent.trace.exporter.SpanExporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * True Tail Sampling SpanProcessor.
 *
 * <p>모든 스팬을 traceId 기준으로 버퍼링하고, 로컬 루트 스팬이 종료될 때
 * 트레이스 전체를 평가하여 export 여부를 결정합니다.
 *
 * <p><b>로컬 루트 감지 전략</b><br>
 * {@code onStart()}에서 이 JVM이 생성한 spanId를 {@link TraceBuffer#localSpanIds}에 등록합니다.
 * {@code onEnd()}에서 스팬의 parentSpanId가 {@code localSpanIds}에 없으면 로컬 루트로 판단합니다.
 * 이를 통해 분산 트레이싱 진입점(SERVER 스팬, remote parent 보유)도 로컬 루트로 올바르게 처리됩니다.
 *
 * <ul>
 *   <li>절대 루트: {@code parentSpanId}가 invalid → 로컬 루트</li>
 *   <li>분산 진입점: {@code parentSpanId}가 원격 서비스 스팬 → 로컬 루트</li>
 *   <li>TTL 만료: 로컬 루트가 오지 않은 미완성 트레이스 → TTL 후 head-sampled 스팬만 export</li>
 *   <li>메모리 보호: pending 트레이스 수를 {@code maxPendingTraces}로 제한</li>
 * </ul>
 */
public final class TailSamplingSpanProcessor implements SpanProcessor {

    private static final int EXPORT_QUEUE_SIZE = 4096;

    private final SpanExporter exporter;
    private final long ttlMs;
    private final long slowThresholdNanos;
    private final Set<String> criticalUrls;
    private final int maxPendingTraces;

    private final ConcurrentHashMap<String, TraceBuffer> pending = new ConcurrentHashMap<>();
    private final BlockingQueue<List<Span>> exportQueue = new ArrayBlockingQueue<>(EXPORT_QUEUE_SIZE);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Thread exportWorker;
    private final ScheduledExecutorService ttlCleaner;

    public TailSamplingSpanProcessor(SpanExporter exporter, AgentConfig config) {
        this.exporter = exporter;
        this.ttlMs = config.getTailSamplingTtlMs();
        this.slowThresholdNanos = TimeUnit.MILLISECONDS.toNanos(config.getSlowThresholdMs());
        this.criticalUrls = Collections.unmodifiableSet(new java.util.HashSet<>(config.getCriticalUrls()));
        this.maxPendingTraces = config.getTailSamplingMaxPendingTraces();

        this.exportWorker = new Thread(this::exportLoop, "javi-tail-export");
        this.exportWorker.setDaemon(true);
        this.exportWorker.start();

        this.ttlCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-tail-ttl-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.ttlCleaner.scheduleAtFixedRate(this::evictStaleTraces, 10, 10, TimeUnit.SECONDS);

        AgentLogger.info("[TailSamplingSpanProcessor] 시작 (TTL=" + ttlMs + "ms, maxPending=" + maxPendingTraces + ")");
    }

    /** 루트 스팬 수신 후 자식 스팬을 기다리는 grace period (ms). */
    private static final long ROOT_GRACE_PERIOD_MS = 100;

    /**
     * 스팬 시작 시 spanId를 로컬 추적 집합에 등록한다.
     *
     * <p>이를 통해 onEnd()에서 parentSpanId가 로컬 스팬인지 원격 스팬인지 판별할 수 있다.
     * 원격 parent를 가진 스팬(분산 트레이싱 진입점 SERVER 스팬)은 로컬 루트로 취급한다.
     */
    @Override
    public void onStart(Span span) {
        if (span == null || shutdown.get()) {
            return;
        }
        String traceId = span.getContext().getTraceId();
        TraceBuffer existing = pending.get(traceId);
        if (existing != null && !existing.decided.get()) {
            existing.localSpanIds.add(span.getContext().getSpanId());
        } else if (existing == null && pending.size() < maxPendingTraces) {
            // 트레이스 버퍼를 미리 생성해 spanId를 등록
            TraceBuffer buf = pending.computeIfAbsent(traceId, k -> new TraceBuffer());
            buf.localSpanIds.add(span.getContext().getSpanId());
        }
    }

    @Override
    public void onEnd(Span span) {
        if (span == null || shutdown.get()) {
            return;
        }
        if (com.agent.config.RemoteConfigHolder.get().isEmergencyOff()) {
            return;
        }

        String traceId = span.getContext().getTraceId();

        // 메모리 보호: 최대 pending 트레이스 초과 시 head sampling 결정 사용
        if (pending.size() >= maxPendingTraces) {
            AgentLogger.debug("[TailSampling] pending 한도 초과, head 결정 사용: traceId=" + traceId);
            if (span.getContext().getTraceFlags().isSampled()) {
                scheduleExport(Collections.singletonList(span));
            }
            return;
        }

        TraceBuffer buf = pending.computeIfAbsent(traceId, k -> new TraceBuffer());

        // 이미 결정된 트레이스에 늦게 도착한 스팬: head-sampled이면 직접 export
        if (buf.decided.get()) {
            if (span.getContext().getTraceFlags().isSampled()) {
                AgentLogger.debug("[TailSampling] 결정 후 늦게 도착한 스팬 직접 export: traceId=" + traceId);
                scheduleExport(Collections.singletonList(span));
            }
            return;
        }

        buf.spans.add(span);

        // 로컬 루트 감지:
        //   1. parentSpanId 없음 → 절대 루트
        //   2. parentSpanId가 로컬 spanId 집합에 없음 → 원격 부모(분산 트레이싱 진입점)
        // 두 경우 모두 이 JVM 내에서 트레이스 결정을 내려야 하는 로컬 루트임.
        String parentSpanId = span.getContext().getParentSpanId();
        boolean isLocalRoot = !SpanId.isValid(parentSpanId)
                || !buf.localSpanIds.contains(parentSpanId);

        if (isLocalRoot && buf.decideScheduled.compareAndSet(false, true)) {
            AgentLogger.debug("[TailSampling] 로컬 루트 감지 (remoteParent=" + SpanId.isValid(parentSpanId)
                    + "), grace=" + ROOT_GRACE_PERIOD_MS + "ms: traceId=" + traceId);
            // Grace period: in-flight 자식 스팬들이 도착할 시간을 허용
            ttlCleaner.schedule(() -> decide(traceId, buf), ROOT_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void decide(String traceId, TraceBuffer buf) {
        if (!buf.decided.compareAndSet(false, true)) {
            return;
        }
        pending.remove(traceId);
        List<Span> snapshot = buf.snapshot();
        if (shouldExport(snapshot)) {
            scheduleExport(snapshot);
        }
    }

    /**
     * 트레이스 export 여부를 결정합니다.
     *
     * <ol>
     *   <li>head-sampled 스팬 존재 → export (ParentBased/AdaptiveSampler 결정 존중)</li>
     *   <li>tail policy: 에러, 느린 응답, 크리티컬 URL</li>
     * </ol>
     */
    private boolean shouldExport(List<Span> spans) {
        Set<String> policy = com.agent.config.RemoteConfigHolder.get().getTailPolicy();

        for (Span span : spans) {
            // head sampler가 이미 SAMPLE 결정 → 존중
            if (span.getContext().getTraceFlags().isSampled()) {
                return true;
            }
            if (!(span instanceof ReadableSpan)) {
                continue;
            }
            ReadableSpan rs = (ReadableSpan) span;

            // 에러 기반
            if (policy.contains("error") && rs.getStatus() == SpanStatus.ERROR) {
                AgentLogger.debug("[TailSampling] 에러 트레이스 export: span=" + rs.getName());
                return true;
            }

            // 지연시간 기반
            if (policy.contains("slow") && slowThresholdNanos > 0) {
                long dur = rs.getEndTimeNanos() - rs.getStartTimeNanos();
                if (dur >= slowThresholdNanos) {
                    AgentLogger.debug("[TailSampling] 느린 트레이스 export: span=" + rs.getName() + " dur=" + TimeUnit.NANOSECONDS.toMillis(dur) + "ms");
                    return true;
                }
            }

            // 크리티컬 URL 기반
            if (policy.contains("critical_url") && !criticalUrls.isEmpty()) {
                String name = rs.getName();
                for (String url : criticalUrls) {
                    if (name.contains(url)) {
                        AgentLogger.debug("[TailSampling] critical_url 트레이스 export: span=" + name);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** TTL 만료 트레이스 처리. 루트 스팬이 오지 않은 미완성 트레이스 정리. */
    private void evictStaleTraces() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TraceBuffer> entry : pending.entrySet()) {
            TraceBuffer buf = entry.getValue();
            if (now - buf.createdMs > ttlMs) {
                if (buf.decided.compareAndSet(false, true)) {
                    pending.remove(entry.getKey());
                    // TTL 만료: head-sampled 스팬만 export (안전한 fallback)
                    List<Span> snapshot = buf.snapshot();
                    List<Span> sampled = new ArrayList<>();
                    for (Span s : snapshot) {
                        if (s.getContext().getTraceFlags().isSampled()) {
                            sampled.add(s);
                        }
                    }
                    if (!sampled.isEmpty()) {
                        AgentLogger.debug("[TailSampling] TTL 만료 트레이스 export: " + sampled.size() + " spans");
                        scheduleExport(sampled);
                    }
                }
            }
        }
    }

    private void scheduleExport(List<Span> spans) {
        if (!exportQueue.offer(spans)) {
            AgentLogger.debug("[TailSampling] export 큐 가득 참, 드롭: " + spans.size() + " spans");
        }
    }

    private void exportLoop() {
        // 루프 밖에 선언하여 매 사이클마다 재사용 (clear()는 내부 배열 유지)
        List<List<Span>> batches = new ArrayList<>(64);
        List<Span> toExport = new ArrayList<>(512);
        while (!shutdown.get()) {
            try {
                List<Span> first = exportQueue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batches.add(first);
                exportQueue.drainTo(batches, 63); // max 64 traces per export call

                // Flatten and export — 기존 리스트 재사용
                for (List<Span> batch : batches) {
                    toExport.addAll(batch);
                }
                batches.clear();

                try {
                    exporter.export(toExport);
                } catch (Throwable t) {
                    AgentLogger.error("[TailSampling] export 오류: " + t.getMessage());
                } finally {
                    toExport.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        // TTL 만료 트레이스 강제 처리
        evictStaleTraces();
        return exporter.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        ttlCleaner.shutdownNow();
        // 남은 pending 트레이스 처리
        for (Map.Entry<String, TraceBuffer> entry : pending.entrySet()) {
            TraceBuffer buf = entry.getValue();
            if (buf.decided.compareAndSet(false, true)) {
                List<Span> snapshot = buf.snapshot();
                if (shouldExport(snapshot)) {
                    scheduleExport(snapshot);
                }
            }
        }
        pending.clear();
        exportWorker.interrupt();
        try {
            exportWorker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return exporter.shutdown();
    }

    /**
     * traceId별 스팬 버퍼. thread-safe.
     *
     * <ul>
     *   <li>{@code spans}: export 후보 스팬 목록 (lock-free)</li>
     *   <li>{@code localSpanIds}: 이 JVM에서 생성된 spanId 집합 — 로컬 루트 판별에 사용</li>
     *   <li>{@code decideScheduled}: decide() 중복 스케줄 방지 플래그</li>
     *   <li>{@code decided}: decide() 실행 여부 플래그 (CAS)</li>
     * </ul>
     */
    private static final class TraceBuffer {
        final java.util.concurrent.ConcurrentLinkedQueue<Span> spans = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.Set<String> localSpanIds =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        final long createdMs = System.currentTimeMillis();
        final AtomicBoolean decideScheduled = new AtomicBoolean(false);
        final AtomicBoolean decided = new AtomicBoolean(false);

        List<Span> snapshot() {
            return new ArrayList<>(spans);
        }
    }
}
