package com.agent.metric;

import com.agent.logs.AgentLogger;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JMX를 통해 주기적으로 JVM 메트릭을 수집해 MetricRegistry에 기록한다.
 *
 * <p>수집 항목:
 * <ul>
 *   <li>jvm.memory.used / committed / limit (heap/nonheap, bytes)</li>
 *   <li>jvm.memory.pool.used / committed / limit (풀별, bytes)</li>
 *   <li>jvm.gc.duration_ms / count (GC 컬렉터별)</li>
 *   <li>jvm.threads.count / peak / daemon</li>
 *   <li>jvm.classes.loaded / unloaded</li>
 *   <li>jvm.cpu.process_load (permille, 0~1000)</li>
 *   <li>system.cpu.load (permille)</li>
 *   <li>system.memory.free / total (bytes)</li>
 * </ul>
 */
public final class JvmMetricsCollector {

    private static volatile ScheduledExecutorService executor;

    private JvmMetricsCollector() {}

    /** JVM 메트릭 수집을 시작한다. 즉시 1회 수집 후 15초 간격으로 반복한다. */
    public static void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-jvm-metrics");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(JvmMetricsCollector::collect, 0, 15, TimeUnit.SECONDS);
        AgentLogger.info("JvmMetricsCollector 시작 (15초 간격)");
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

            collectMemory();
            collectMemoryPools();

            if (!"jvm_only".equals(metricsScope)) {
                collectGc();
                collectThreads();
                collectClasses();
                collectCpu();
                collectSystemMemory();
            }

            MetricRegistry.get().scrapeAndEmit();
        } catch (Throwable t) {
            AgentLogger.debug("[jvm-metrics] 수집 오류: " + t.getMessage());
        }
    }

    private static void collectMemory() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MetricRegistry reg = MetricRegistry.get();

        MemoryUsage heap = memory.getHeapMemoryUsage();
        reg.gauge("jvm.memory.used", attr("area", "heap")).set(heap.getUsed());
        reg.gauge("jvm.memory.committed", attr("area", "heap")).set(heap.getCommitted());
        if (heap.getMax() > 0) {
            reg.gauge("jvm.memory.limit", attr("area", "heap")).set(heap.getMax());
        }

        MemoryUsage nonheap = memory.getNonHeapMemoryUsage();
        reg.gauge("jvm.memory.used", attr("area", "nonheap")).set(nonheap.getUsed());
        reg.gauge("jvm.memory.committed", attr("area", "nonheap")).set(nonheap.getCommitted());
    }

    private static void collectMemoryPools() {
        MetricRegistry reg = MetricRegistry.get();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : pools) {
            String poolName = pool.getName();
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;

            Map<String, String> tags = attr("pool", poolName);
            reg.gauge("jvm.memory.pool.used", tags).set(usage.getUsed());
            reg.gauge("jvm.memory.pool.committed", tags).set(usage.getCommitted());
            if (usage.getMax() > 0) {
                reg.gauge("jvm.memory.pool.limit", tags).set(usage.getMax());
            }
        }
    }

    private static void collectGc() {
        MetricRegistry reg = MetricRegistry.get();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long elapsed = gc.getCollectionTime();
            if (count < 0) continue;

            Map<String, String> tags = attr("gc", gc.getName());
            reg.gauge("jvm.gc.count", tags).set(count);
            reg.gauge("jvm.gc.duration_ms", tags).set(elapsed >= 0 ? elapsed : 0);
        }
    }

    private static void collectThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MetricRegistry reg = MetricRegistry.get();
        reg.gauge("jvm.threads.count", Collections.emptyMap()).set(threads.getThreadCount());
        reg.gauge("jvm.threads.peak", Collections.emptyMap()).set(threads.getPeakThreadCount());
        reg.gauge("jvm.threads.daemon", Collections.emptyMap()).set(threads.getDaemonThreadCount());
    }

    private static void collectClasses() {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        MetricRegistry reg = MetricRegistry.get();
        reg.gauge("jvm.classes.loaded", Collections.emptyMap()).set(cl.getLoadedClassCount());
        reg.gauge("jvm.classes.unloaded", Collections.emptyMap()).set(cl.getUnloadedClassCount());
    }

    private static void collectCpu() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            java.lang.reflect.Method processCpuLoad = os.getClass().getDeclaredMethod("getProcessCpuLoad");
            processCpuLoad.setAccessible(true);
            double cpuLoad = (double) processCpuLoad.invoke(os);
            if (cpuLoad >= 0) {
                MetricRegistry.get().gauge("jvm.cpu.process_load", Collections.emptyMap())
                        .set((long) (cpuLoad * 1000)); // permille (0~1000)
            }

            // 시스템 전체 CPU (JDK 14+ getCpuLoad, 이하 getSystemCpuLoad)
            double systemLoad = -1;
            try {
                java.lang.reflect.Method getCpuLoad = os.getClass().getDeclaredMethod("getCpuLoad");
                getCpuLoad.setAccessible(true);
                systemLoad = (double) getCpuLoad.invoke(os);
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Method getSystemCpuLoad = os.getClass().getDeclaredMethod("getSystemCpuLoad");
                getSystemCpuLoad.setAccessible(true);
                systemLoad = (double) getSystemCpuLoad.invoke(os);
            }
            if (systemLoad >= 0) {
                MetricRegistry.get().gauge("system.cpu.load", Collections.emptyMap())
                        .set((long) (systemLoad * 1000));
            }
        } catch (Throwable ignored) {}
    }

    private static void collectSystemMemory() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

            java.lang.reflect.Method freeMemory = os.getClass().getDeclaredMethod("getFreePhysicalMemorySize");
            freeMemory.setAccessible(true);
            long free = (long) freeMemory.invoke(os);

            java.lang.reflect.Method totalMemory = os.getClass().getDeclaredMethod("getTotalPhysicalMemorySize");
            totalMemory.setAccessible(true);
            long total = (long) totalMemory.invoke(os);

            MetricRegistry reg = MetricRegistry.get();
            reg.gauge("system.memory.free", Collections.emptyMap()).set(free);
            reg.gauge("system.memory.total", Collections.emptyMap()).set(total);
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> attr(String key, String value) {
        Map<String, String> m = new HashMap<>(2);
        m.put(key, value);
        return m;
    }
}
