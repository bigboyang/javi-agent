package com.agent.metric;

import com.agent.logs.AgentLogger;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Tomcat / Jetty 스레드풀 메트릭을 JMX를 통해 수집한다.
 *
 * <p>스레드풀 포화도는 레이턴시 급증의 핵심 원인 중 하나.
 * {@code tomcat.threads.utilization}이 0.8 초과 시 요청 큐잉 시작.
 *
 * <p>수집 항목 (connector 태그 포함):
 * <ul>
 *   <li>tomcat.threads.active    — 현재 요청 처리 중인 스레드</li>
 *   <li>tomcat.threads.idle      — 유휴 스레드 수</li>
 *   <li>tomcat.threads.max       — 최대 설정 스레드 수</li>
 *   <li>tomcat.threads.current   — 현재 생성된 총 스레드 수</li>
 *   <li>tomcat.connections.count — 현재 활성 커넥션 수</li>
 * </ul>
 *
 * <p>Jetty: {@code org.eclipse.jetty.util.thread:type=queuedthreadpool,id=0}에서
 * threads, idleThreads, maxThreads를 수집한다.
 */
public final class TomcatMetricsCollector {

    private static final ConcurrentHashMap<String, Long> lastRequestCount   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastErrorCount     = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastProcessingTime = new ConcurrentHashMap<>();

    private TomcatMetricsCollector() {}

    public static void start() {}

    public static void stop() {}

    static void collect() {
        try {
            String metricsScope = com.agent.config.RemoteConfigHolder.get().getMetrics();
            if ("disabled".equals(metricsScope)) return;

            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            boolean collected = false;

            collected |= collectTomcat(mbs);
            collectGlobalRequestProcessor(mbs);
            if (!collected) collectJetty(mbs);
        } catch (Throwable t) {
            AgentLogger.debug("[tomcat-metrics] 수집 오류: " + t.getMessage());
        }
    }

    /** Tomcat NIO 커넥터 ThreadPool MBean 수집 */
    private static boolean collectTomcat(MBeanServer mbs) {
        try {
            // Catalina:type=ThreadPool,name="http-nio-8080"
            Set<ObjectName> beans = mbs.queryNames(
                    new ObjectName("Catalina:type=ThreadPool,name=*"), null);
            if (beans.isEmpty()) return false;

            for (ObjectName bean : beans) {
                String connectorName = extractConnectorName(bean.getKeyProperty("name"));
                Map<String, String> tags = attr("connector", connectorName);
                MetricRegistry reg = MetricRegistry.get();

                Object currentThreadCount = safeGet(mbs, bean, "currentThreadCount");
                Object currentThreadsBusy = safeGet(mbs, bean, "currentThreadsBusy");
                Object maxThreads         = safeGet(mbs, bean, "maxThreads");
                Object connectionCount    = safeGet(mbs, bean, "connectionCount");

                long current = toLong(currentThreadCount, -1);
                long busy    = toLong(currentThreadsBusy, -1);
                long max     = toLong(maxThreads, -1);

                if (current >= 0) reg.gauge("tomcat.threads.current", tags).set(current);
                if (busy    >= 0) reg.gauge("tomcat.threads.active",  tags).set(busy);
                if (current >= 0 && busy >= 0) {
                    reg.gauge("tomcat.threads.idle", tags).set(Math.max(0, current - busy));
                }
                if (max     >= 0) reg.gauge("tomcat.threads.max", tags).set(max);
                if (connectionCount instanceof Number) {
                    reg.gauge("tomcat.connections.count", tags).set(((Number) connectionCount).longValue());
                }
            }
            return true;
        } catch (Throwable t) {
            AgentLogger.debug("[tomcat-metrics] Tomcat MBean 수집 오류: " + t.getMessage());
            return false;
        }
    }

    /** Catalina:type=GlobalRequestProcessor — request/error count, processing time */
    private static void collectGlobalRequestProcessor(MBeanServer mbs) {
        try {
            Set<ObjectName> beans = mbs.queryNames(
                    new ObjectName("Catalina:type=GlobalRequestProcessor,name=*"), null);
            if (beans.isEmpty()) return;

            MetricRegistry reg = MetricRegistry.get();
            for (ObjectName bean : beans) {
                String connName = extractConnectorName(bean.getKeyProperty("name"));
                Map<String, String> tags = attr("connector", connName);

                long reqCount  = toLong(safeGet(mbs, bean, "requestCount"), -1);
                long errCount  = toLong(safeGet(mbs, bean, "errorCount"), -1);
                long procTime  = toLong(safeGet(mbs, bean, "processingTime"), -1);

                if (reqCount >= 0) {
                    long prev  = lastRequestCount.getOrDefault(connName, 0L);
                    long delta = Math.max(0L, reqCount - prev);
                    if (delta > 0) reg.counter("tomcat.request.count", "Total requests processed", "1", tags).add(delta);
                    lastRequestCount.put(connName, reqCount);
                }
                if (errCount >= 0) {
                    long prev  = lastErrorCount.getOrDefault(connName, 0L);
                    long delta = Math.max(0L, errCount - prev);
                    if (delta > 0) reg.counter("tomcat.request.error_count", "Total request errors", "1", tags).add(delta);
                    lastErrorCount.put(connName, errCount);
                }
                if (procTime >= 0) {
                    long prev  = lastProcessingTime.getOrDefault(connName, 0L);
                    long delta = Math.max(0L, procTime - prev);
                    if (delta > 0) reg.counter("tomcat.request.processing_time", "Total request processing time", "ms", tags).add(delta);
                    lastProcessingTime.put(connName, procTime);
                }
            }
        } catch (Throwable t) {
            AgentLogger.debug("[tomcat-metrics] GlobalRequestProcessor 수집 오류: " + t.getMessage());
        }
    }

    /** Jetty QueuedThreadPool MBean 수집 */
    private static boolean collectJetty(MBeanServer mbs) {
        try {
            Set<ObjectName> beans = mbs.queryNames(
                    new ObjectName("org.eclipse.jetty.util.thread:type=queuedthreadpool,*"), null);
            if (beans.isEmpty()) return false;

            for (ObjectName bean : beans) {
                String id = bean.getKeyProperty("id");
                Map<String, String> tags = attr("connector", "jetty-" + (id != null ? id : "0"));
                MetricRegistry reg = MetricRegistry.get();

                Object threads     = safeGet(mbs, bean, "threads");
                Object idleThreads = safeGet(mbs, bean, "idleThreads");
                Object maxThreads  = safeGet(mbs, bean, "maxThreads");

                long current = toLong(threads, -1);
                long idle    = toLong(idleThreads, -1);
                long max     = toLong(maxThreads, -1);

                if (current >= 0) reg.gauge("jetty.threads.current", tags).set(current);
                if (idle    >= 0) reg.gauge("jetty.threads.idle", tags).set(idle);
                if (current >= 0 && idle >= 0) {
                    reg.gauge("jetty.threads.active", tags).set(Math.max(0, current - idle));
                }
                if (max     >= 0) reg.gauge("jetty.threads.max", tags).set(max);
            }
            return true;
        } catch (Throwable t) {
            AgentLogger.debug("[tomcat-metrics] Jetty MBean 수집 오류: " + t.getMessage());
            return false;
        }
    }

    /** "http-nio-8080" 형식에서 커넥터 이름 정규화 */
    private static String extractConnectorName(String raw) {
        if (raw == null) return "default";
        // 따옴표 제거
        return raw.replace("\"", "").trim();
    }

    private static Object safeGet(MBeanServer mbs, ObjectName name, String attribute) {
        try {
            return mbs.getAttribute(name, attribute);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long toLong(Object val, long defaultVal) {
        if (val instanceof Number) return ((Number) val).longValue();
        return defaultVal;
    }

    private static Map<String, String> attr(String key, String value) {
        Map<String, String> m = new HashMap<>(2);
        m.put(key, value);
        return m;
    }
}
