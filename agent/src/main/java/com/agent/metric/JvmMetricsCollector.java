package com.agent.metric;

import com.agent.logs.AgentLogger;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JMX를 통해 주기적으로 JVM 메트릭을 수집해 MetricRegistry에 기록한다.
 *
 * <p>수집 항목:
 * <ul>
 *   <li>jvm.memory.heap.used / committed / max (bytes)</li>
 *   <li>jvm.memory.nonheap.used (bytes)</li>
 *   <li>jvm.gc.collections.count / elapsed_ms (총 누적)</li>
 *   <li>jvm.threads.count / peak</li>
 *   <li>jvm.cpu.process_load (0.0~1.0, com.sun.management 사용 가능 시)</li>
 * </ul>
 */
public final class JvmMetricsCollector {

    private static volatile ScheduledExecutorService executor;

    private JvmMetricsCollector() {}

    /**
     * JVM 메트릭 수집을 시작한다. 즉시 1회 수집 후 15초 간격으로 반복한다.
     */
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
            if ("disabled".equals(metricsScope)) {
                return;
            }

            collectMemory();
            if (!"jvm_only".equals(metricsScope)) {
                collectGc();
                collectThreads();
                collectCpu();
            }

            // 수집 완료 후 파이프라인으로 전송
            MetricRegistry.get().scrapeAndEmit();
        } catch (Throwable t) {
            AgentLogger.debug("[jvm-metrics] 수집 오류: " + t.getMessage());
        }
    }

    private static void collectMemory() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MetricRegistry reg = MetricRegistry.get();
        
        // 속성(Tag) 예: { type: heap }
        reg.gauge("jvm.memory.used", java.util.Collections.singletonMap("area", "heap")).set(heap.getUsed());
        reg.gauge("jvm.memory.used", java.util.Collections.singletonMap("area", "nonheap")).set(memory.getNonHeapMemoryUsage().getUsed());
    }

    private static void collectGc() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalCount = 0;
        long totalElapsedMs = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long elapsed = gc.getCollectionTime();
            if (count >= 0) totalCount += count;
            if (elapsed >= 0) totalElapsedMs += elapsed;
        }
        MetricRegistry reg = MetricRegistry.get();
        reg.gauge("jvm.gc.collections.count", java.util.Collections.emptyMap()).set(totalCount);
        reg.gauge("jvm.gc.collections.elapsed_ms", java.util.Collections.emptyMap()).set(totalElapsedMs);
    }

    private static void collectThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MetricRegistry reg = MetricRegistry.get();
        reg.gauge("jvm.threads.count", java.util.Collections.emptyMap()).set(threads.getThreadCount());
        reg.gauge("jvm.threads.peak", java.util.Collections.emptyMap()).set(threads.getPeakThreadCount());
        reg.gauge("jvm.threads.daemon", java.util.Collections.emptyMap()).set(threads.getDaemonThreadCount());
    }

    private static void collectCpu() {
        try {
            // com.sun.management.OperatingSystemMXBean 확장을 reflection으로 접근
            // (JDK 내부 API이므로 직접 import 대신 reflection 사용)
            java.lang.management.OperatingSystemMXBean os =
                    ManagementFactory.getOperatingSystemMXBean();
            java.lang.reflect.Method processCpuLoad =
                    os.getClass().getDeclaredMethod("getProcessCpuLoad");
            processCpuLoad.setAccessible(true);
            double cpuLoad = (double) processCpuLoad.invoke(os);
            if (cpuLoad >= 0) {
                MetricRegistry.get().gauge("jvm.cpu.process_load", java.util.Collections.emptyMap()).set((long)(cpuLoad * 1000)); // gauge는 long을 받으므로 비율로 변환
            }
        } catch (Throwable ignored) {}
    }
}
