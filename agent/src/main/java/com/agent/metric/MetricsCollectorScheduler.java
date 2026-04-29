package com.agent.metric;

import com.agent.logs.AgentLogger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 모든 메트릭 수집기를 단일 스레드로 조율하는 스케줄러.
 *
 * <p>기존 설계의 문제:
 * <ul>
 *   <li>JVM/Tomcat/Hikari/K8s 수집기 각각 독립 ScheduledExecutorService 보유 → 4개 데몬 스레드 낭비</li>
 *   <li>각 수집기 collect() 마지막에 scrapeAndEmit() 호출 → 15초 간격마다 최대 4회 중복 전송,
 *       다른 수집기가 아직 MetricRegistry를 갱신하기 전 부분 스냅샷이 전송됨</li>
 * </ul>
 *
 * <p>개선:
 * <ul>
 *   <li>단일 "javi-metrics-scheduler" 스레드로 모든 수집기 순차 실행</li>
 *   <li>모든 수집기 완료 후 scrapeAndEmit() 1회 → 동일 타임스탬프 원자적 스냅샷 보장</li>
 *   <li>스레드 수 4 → 1 절감</li>
 * </ul>
 *
 * <p>환경변수:
 * <ul>
 *   <li>{@code JAVI_METRICS_INTERVAL_SEC} — 수집 주기 초 (기본: 15)</li>
 * </ul>
 */
public final class MetricsCollectorScheduler {

    private static final String ENV_INTERVAL_SEC     = "JAVI_METRICS_INTERVAL_SEC";
    private static final long   DEFAULT_INTERVAL_SEC = 15L;

    private static volatile ScheduledExecutorService executor;

    private MetricsCollectorScheduler() {}

    /**
     * 단일 스케줄러를 시작한다.
     *
     * <p>초기 수집은 즉시 실행하지 않고 {@code intervalSec} 후부터 시작한다.
     * Tomcat/HikariCP MBean은 애플리케이션 완전 기동 후 등록되므로,
     * 첫 수집이 약간 지연되어도 각 수집기 내부에서 MBean 미등록 시 graceful skip한다.
     */
    public static synchronized void start() {
        if (executor != null) return;
        long intervalSec = parseLong(ENV_INTERVAL_SEC, DEFAULT_INTERVAL_SEC);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-metrics-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(MetricsCollectorScheduler::collectAll, 0, intervalSec, TimeUnit.SECONDS);
        AgentLogger.info("[MetricsScheduler] 시작 — 주기: " + intervalSec + "s");
    }

    public static void stop() {
        ScheduledExecutorService ex = executor;
        if (ex != null) {
            ex.shutdown();
        }
    }

    /**
     * 모든 수집기를 순차 실행한 뒤 scrapeAndEmit() 1회 호출.
     *
     * <p>각 수집기는 MetricRegistry를 갱신하기만 하고 scrapeAndEmit()을 호출하지 않는다.
     * 이 메서드가 모든 갱신 완료 후 단 1회 전송함으로써 완전한 스냅샷을 보장한다.
     */
    static void collectAll() {
        try {
            String metricsScope = com.agent.config.RemoteConfigHolder.get().getMetrics();
            if ("disabled".equals(metricsScope)) return;

            JvmMetricsCollector.collect();
            TomcatMetricsCollector.collect();
            HikariMetricsCollector.collect();
            K8sMetricsCollector.collect();

            MetricRegistry.get().scrapeAndEmit();
        } catch (Throwable t) {
            AgentLogger.debug("[MetricsScheduler] 수집 오류: " + t.getMessage());
        }
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
