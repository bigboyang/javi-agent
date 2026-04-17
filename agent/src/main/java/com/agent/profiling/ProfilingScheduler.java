package com.agent.profiling;

import com.agent.logs.AgentLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuous Profiling 주기적 스케줄러 (GAP-07).
 *
 * <p>일정 주기마다 {@link ProfilingExporter#collectAndExport()}를 호출하여
 * CPU 프로파일링 스냅샷을 수집하고 javi-collector에 전송한다.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>ByteBuddy 불필요 — 순수 Java 백그라운드 스레드로 동작</li>
 *   <li>데몬 스레드 사용 — JVM 종료를 막지 않음</li>
 *   <li>스케줄 주기(interval)와 수집 시간(duration)을 분리 관리</li>
 *   <li>동시 실행 방지 — 이전 수집이 진행 중이면 다음 수집을 건너뜀</li>
 * </ul>
 *
 * <p>기본 동작 (환경변수 미설정 시):
 * <ul>
 *   <li>초기 지연: 60초 (애플리케이션 워밍업 대기)</li>
 *   <li>수집 주기: 30초마다 1회</li>
 *   <li>수집 시간: 10초 (ProfilingExporter 기본값)</li>
 * </ul>
 *
 * <p>환경변수:
 * <ul>
 *   <li>{@code JAVI_PROFILING_INTERVAL_SEC} — 수집 주기 초 (기본: 30)</li>
 *   <li>{@code JAVI_PROFILING_INITIAL_DELAY_SEC} — 최초 수집까지 대기 초 (기본: 60)</li>
 *   <li>{@code JAVI_PROFILING_ENABLED} — "false"이면 스케줄러 자체를 시작하지 않음</li>
 * </ul>
 */
public final class ProfilingScheduler {

    private static final String ENV_INTERVAL_SEC     = "JAVI_PROFILING_INTERVAL_SEC";
    private static final String ENV_INITIAL_DELAY_SEC = "JAVI_PROFILING_INITIAL_DELAY_SEC";
    private static final String ENV_ENABLED          = "JAVI_PROFILING_ENABLED";

    private static final long DEFAULT_INTERVAL_SEC      = 30L;
    private static final long DEFAULT_INITIAL_DELAY_SEC = 60L;

    /** 싱글턴 스케줄러 — premain()에서 한 번만 시작 */
    private static volatile ProfilingScheduler instance;
    private static final Object LOCK = new Object();

    private final ScheduledExecutorService scheduler;
    private final ProfilingExporter         exporter;
    private final AtomicBoolean             running = new AtomicBoolean(false);
    private ScheduledFuture<?>              scheduledTask;

    private ProfilingScheduler(ProfilingExporter exporter) {
        this.exporter = exporter;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-profiling-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 스케줄러를 초기화하고 시작한다.
     *
     * <p>{@code JAVI_PROFILING_ENABLED=false}인 경우 아무것도 하지 않는다.
     * 이미 시작된 경우에도 재진입을 막는다.
     */
    public static void start() {
        if ("false".equalsIgnoreCase(System.getenv(ENV_ENABLED))) {
            AgentLogger.info("[profiling] JAVI_PROFILING_ENABLED=false — 프로파일링 비활성화");
            return;
        }

        if (instance != null) return;
        synchronized (LOCK) {
            if (instance != null) return;
            instance = new ProfilingScheduler(new ProfilingExporter());
            instance.scheduleCollection();
        }
    }

    /**
     * 스케줄러를 중지하고 자원을 해제한다.
     * JVM ShutdownHook에서 호출된다.
     */
    public static void stop() {
        ProfilingScheduler s = instance;
        if (s != null) {
            s.shutdown();
        }
    }

    private void scheduleCollection() {
        long intervalSec     = parseLong(ENV_INTERVAL_SEC, DEFAULT_INTERVAL_SEC);
        long initialDelaySec = parseLong(ENV_INITIAL_DELAY_SEC, DEFAULT_INITIAL_DELAY_SEC);

        AgentLogger.info(String.format(
                "[profiling] 스케줄러 시작 — 초기 지연: %ds, 주기: %ds, 수집 시간: %dms",
                initialDelaySec, intervalSec,
                parseLong("JAVI_PROFILING_SAMPLE_DURATION_MS", 10_000L)));

        scheduledTask = scheduler.scheduleAtFixedRate(
                this::safeCollectAndExport,
                initialDelaySec,
                intervalSec,
                TimeUnit.SECONDS);
    }

    /**
     * 동시 실행 방지가 적용된 수집 래퍼.
     *
     * <p>이전 수집이 아직 진행 중이면 현재 주기는 건너뛴다.
     * (수집 시간이 주기보다 길어질 경우의 안전장치)
     */
    private void safeCollectAndExport() {
        if (!running.compareAndSet(false, true)) {
            AgentLogger.debug("[profiling] 이전 수집 진행 중 — 현재 주기 건너뜀");
            return;
        }
        try {
            exporter.collectAndExport();
        } catch (Throwable t) {
            AgentLogger.warn("[profiling] 수집 중 예외: " + t.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void shutdown() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        AgentLogger.info("[profiling] 스케줄러 종료 완료");
    }

    private static long parseLong(String envKey, long defaultVal) {
        try {
            String v = System.getenv(envKey);
            return (v != null && !v.isEmpty()) ? Long.parseLong(v) : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
