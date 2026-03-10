package com.agent.metric;

import com.agent.logs.AgentLogger;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * HikariCP 커넥션 풀 메트릭을 JMX MBean을 통해 수집한다.
 *
 * <p>HikariCP가 JMX를 노출하려면 다음 설정이 필요하다:
 * <pre>spring.datasource.hikari.register-mbeans=true</pre>
 *
 * <p>수집 항목 (pool별 태그 포함):
 * <ul>
 *   <li>hikaricp.connections.active  - 현재 사용 중인 커넥션 수</li>
 *   <li>hikaricp.connections.idle    - 유휴 커넥션 수</li>
 *   <li>hikaricp.connections.pending - 커넥션 대기 중인 스레드 수</li>
 *   <li>hikaricp.connections.total   - 전체 커넥션 수</li>
 *   <li>hikaricp.connections.max     - 최대 풀 크기</li>
 *   <li>hikaricp.connections.min     - 최소 유휴 커넥션</li>
 *   <li>hikaricp.connections.timeout_total - 커넥션 획득 타임아웃 누적 수</li>
 * </ul>
 */
public final class HikariMetricsCollector {

    private static volatile ScheduledExecutorService executor;

    private HikariMetricsCollector() {}

    /**
     * HikariCP 메트릭 수집을 시작한다.
     * 애플리케이션이 초기화된 후 MBean이 등록되므로 초기 딜레이 10초 후 15초 간격으로 수집한다.
     */
    public static void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-hikari-metrics");
            t.setDaemon(true);
            return t;
        });
        // 앱 초기화 후 MBean이 등록될 시간을 주기 위해 10초 딜레이
        executor.scheduleAtFixedRate(HikariMetricsCollector::collect, 10, 15, TimeUnit.SECONDS);
        AgentLogger.info("HikariMetricsCollector 시작 (10초 딜레이 후 15초 간격)");
    }

    public static void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    static void collect() {
        try {
            String metricsScope = com.agent.config.RemoteConfigHolder.get().getMetrics();
            if ("disabled".equals(metricsScope)) return;

            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

            // com.zaxxer.hikari:type=Pool (*) 패턴으로 모든 HikariCP 풀 탐색
            Set<ObjectName> poolBeans = mbs.queryNames(
                    new ObjectName("com.zaxxer.hikari:type=Pool (*),*"), null);

            if (poolBeans.isEmpty()) {
                // PoolConfig MBean도 시도 (HikariCP 버전에 따라 이름이 다를 수 있음)
                poolBeans = mbs.queryNames(
                        new ObjectName("com.zaxxer.hikari:type=PoolConfig (*),*"), null);
                if (poolBeans.isEmpty()) return;
            }

            for (ObjectName poolName : poolBeans) {
                collectPool(mbs, poolName);
            }

            MetricRegistry.get().scrapeAndEmit();
        } catch (Throwable t) {
            AgentLogger.debug("[hikari-metrics] 수집 오류: " + t.getMessage());
        }
    }

    private static void collectPool(MBeanServer mbs, ObjectName poolBean) {
        try {
            // Pool 이름 추출: "com.zaxxer.hikari:type=Pool (HikariPool-1)" -> "HikariPool-1"
            String poolId = poolBean.getKeyProperty("type");
            if (poolId != null && poolId.startsWith("Pool (") && poolId.endsWith(")")) {
                poolId = poolId.substring(6, poolId.length() - 1);
            }
            Map<String, String> tags = attr("pool", poolId != null ? poolId : "default");

            MetricRegistry reg = MetricRegistry.get();

            Object active = safeGet(mbs, poolBean, "ActiveConnections");
            Object idle = safeGet(mbs, poolBean, "IdleConnections");
            Object pending = safeGet(mbs, poolBean, "ThreadsAwaitingConnection");
            Object total = safeGet(mbs, poolBean, "TotalConnections");

            if (active instanceof Number) reg.gauge("hikaricp.connections.active", tags).set(((Number) active).longValue());
            if (idle instanceof Number) reg.gauge("hikaricp.connections.idle", tags).set(((Number) idle).longValue());
            if (pending instanceof Number) reg.gauge("hikaricp.connections.pending", tags).set(((Number) pending).longValue());
            if (total instanceof Number) reg.gauge("hikaricp.connections.total", tags).set(((Number) total).longValue());

            // PoolConfig MBean에서 max/min 읽기
            ObjectName configBean = new ObjectName(
                    poolBean.getDomain() + ":type=" + poolBean.getKeyProperty("type").replace("Pool (", "PoolConfig ("));
            if (mbs.isRegistered(configBean)) {
                Object max = safeGet(mbs, configBean, "MaximumPoolSize");
                Object min = safeGet(mbs, configBean, "MinimumIdle");
                if (max instanceof Number) reg.gauge("hikaricp.connections.max", tags).set(((Number) max).longValue());
                if (min instanceof Number) reg.gauge("hikaricp.connections.min", tags).set(((Number) min).longValue());
            }
        } catch (Throwable t) {
            AgentLogger.debug("[hikari-metrics] pool 수집 오류: " + t.getMessage());
        }
    }

    private static Object safeGet(MBeanServer mbs, ObjectName name, String attribute) {
        try {
            return mbs.getAttribute(name, attribute);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> attr(String key, String value) {
        Map<String, String> m = new HashMap<>(2);
        m.put(key, value);
        return m;
    }
}
