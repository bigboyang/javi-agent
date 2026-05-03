package com.agent.metric;

import com.agent.logs.AgentLogger;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * JMX를 통해 주기적으로 JVM 메트릭을 수집해 MetricRegistry에 기록한다.
 *
 * <p>수집 항목:
 * <ul>
 *   <li>jvm.memory.used / committed / limit (heap/nonheap, bytes)</li>
 *   <li>jvm.memory.pool.used / committed / limit (풀별, bytes)</li>
 *   <li>jvm.gc.collections.count / elapsed (누적 값, SUM)</li>
 *   <li>jvm.gc.duration (개별 pause time, HISTOGRAM)</li>
 *   <li>jvm.threads.count / peak / daemon</li>
 *   <li>jvm.classes.loaded / unloaded</li>
 *   <li>jvm.cpu.process_load (permille, 0~1000)</li>
 *   <li>system.cpu.load (permille)</li>
 *   <li>system.memory.free / total (bytes)</li>
 * </ul>
 */
public final class JvmMetricsCollector {

    // GC 직전 값을 보관 → delta를 Counter에 add (누적 → SUM/Monotonic 전환)
    private static final ConcurrentHashMap<String, Long> lastGcCount   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastGcElapsed = new ConcurrentHashMap<>();

    // Reflection 결과 캐싱 — volatile + single-check lazy-init (JedisAdvice 패턴 동일)
    private static volatile java.lang.reflect.Method PROCESS_CPU_LOAD_METHOD;
    private static volatile java.lang.reflect.Method SYSTEM_CPU_LOAD_METHOD;
    private static volatile java.lang.reflect.Method FREE_PHYSICAL_MEMORY_METHOD;
    private static volatile java.lang.reflect.Method TOTAL_PHYSICAL_MEMORY_METHOD;

    private JvmMetricsCollector() {}

    /**
     * GC 이벤트 기반 Notification Listener를 등록한다.
     * 주기적 수집은 {@link MetricsCollectorScheduler}가 담당한다.
     */
    public static synchronized void start() {
        registerGcNotificationListener();
        AgentLogger.info("JvmMetricsCollector 시작 (GC Listener 등록, 스케줄링은 MetricsCollectorScheduler)");
    }

    private static void registerGcNotificationListener() {
        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                if (gcBean instanceof NotificationEmitter) {
                    NotificationEmitter emitter = (NotificationEmitter) gcBean;
                    NotificationListener listener = (notification, handback) -> {
                        if (notification.getType().equals("com.sun.management.gc.notification")) {
                            try {
                                // GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                                // 리플렉션 없이 CompositeData에서 직접 필요한 필드 추출 (호환성 상향)
                                CompositeData cd = (CompositeData) notification.getUserData();
                                CompositeData gcInfo = (CompositeData) cd.get("gcInfo");
                                long duration = (long) gcInfo.get("duration");
                                String gcName = (String) cd.get("gcName");
                                String gcAction = (String) cd.get("gcAction");
                                String gcCause = (String) cd.get("gcCause");

                                Map<String, String> tags = new HashMap<>(4);
                                tags.put("gc", gcName);
                                tags.put("gc.action", gcAction);
                                tags.put("gc.type", classifyGc(gcName));
                                if (gcCause != null && !gcCause.isEmpty()) {
                                    tags.put("gc.cause", gcCause);
                                }

                                // 개별 GC pause time을 히스토그램으로 기록 (OTel 표준)
                                MetricRegistry.get().histogram("jvm.gc.duration", "Duration of JVM garbage collection pauses", "ms", tags)
                                        .record(duration);
                            } catch (Throwable ignored) {}
                        }
                    };
                    emitter.addNotificationListener(listener, null, null);
                }
            }
        } catch (Throwable t) {
            AgentLogger.debug("GC NotificationListener 등록 실패: " + t.getMessage());
        }
    }

    public static void stop() {}

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
                collectBufferPools();
                collectFileDescriptors();
            }
        } catch (Throwable t) {
            AgentLogger.debug("[jvm-metrics] 수집 오류: " + t.getMessage());
        }
    }

    private static void collectMemory() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MetricRegistry reg = MetricRegistry.get();

        MemoryUsage heap = memory.getHeapMemoryUsage();
        reg.gauge("jvm.memory.used", "Heap memory used", "By", attr("area", "heap")).set(heap.getUsed());
        reg.gauge("jvm.memory.committed", "Heap memory committed", "By", attr("area", "heap")).set(heap.getCommitted());
        if (heap.getMax() > 0) {
            reg.gauge("jvm.memory.limit", "Heap memory limit", "By", attr("area", "heap")).set(heap.getMax());
        }

        MemoryUsage nonheap = memory.getNonHeapMemoryUsage();
        reg.gauge("jvm.memory.used", "Non-heap memory used", "By", attr("area", "nonheap")).set(nonheap.getUsed());
        reg.gauge("jvm.memory.committed", "Non-heap memory committed", "By", attr("area", "nonheap")).set(nonheap.getCommitted());
    }

    private static void collectMemoryPools() {
        MetricRegistry reg = MetricRegistry.get();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : pools) {
            String poolName = pool.getName();
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;

            Map<String, String> tags = attr("pool", poolName);
            reg.gauge("jvm.memory.pool.used", "Memory pool used", "By", tags).set(usage.getUsed());
            reg.gauge("jvm.memory.pool.committed", "Memory pool committed", "By", tags).set(usage.getCommitted());
            if (usage.getMax() > 0) {
                reg.gauge("jvm.memory.pool.limit", "Memory pool limit", "By", tags).set(usage.getMax());
            }
        }
    }

    private static void collectGc() {
        MetricRegistry reg = MetricRegistry.get();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count   = gc.getCollectionCount();
            long elapsed = gc.getCollectionTime();
            if (count < 0) continue;

            String gcName  = gc.getName();
            String gcType  = classifyGc(gcName);
            Map<String, String> tags = new HashMap<>(4);
            tags.put("gc", gcName);
            tags.put("gc.type", gcType);

            // OTel 표준 메트릭 이름으로 변경: jvm.gc.count -> jvm.gc.collections.count
            // jvm.gc.duration_ms -> jvm.gc.collections.elapsed
            long prevCount   = lastGcCount.getOrDefault(gcName, 0L);
            long prevElapsed = lastGcElapsed.getOrDefault(gcName, 0L);
            long deltaCount   = Math.max(0L, count   - prevCount);
            long deltaElapsed = Math.max(0L, (elapsed >= 0 ? elapsed : 0) - prevElapsed);

            if (deltaCount > 0) {
                reg.counter("jvm.gc.collections.count", "Total number of GC collections", "1", tags).add(deltaCount);
                reg.counter("jvm.gc.collections.elapsed", "Total time spent in GC", "ms", tags).add(deltaElapsed);
                lastGcCount.put(gcName, count);
                lastGcElapsed.put(gcName, elapsed >= 0 ? elapsed : 0);
            } else if (!lastGcCount.containsKey(gcName)) {
                lastGcCount.put(gcName, count);
                lastGcElapsed.put(gcName, elapsed >= 0 ? elapsed : 0);
            }
        }
    }

    /** GC 컬렉터 이름에서 Young(minor) / Old(major) 분류 */
    private static String classifyGc(String gcName) {
        if (gcName == null) return "unknown";
        String lc = gcName.toLowerCase();
        if (lc.contains("young") || lc.contains("scavenge")
                || lc.contains("parnew") || lc.contains("minor")
                || lc.contains("copy")) {
            return "young";
        }
        return "old";
    }

    private static void collectThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MetricRegistry reg = MetricRegistry.get();
        reg.gauge("jvm.threads.count", "Current thread count", "{thread}", Collections.emptyMap()).set(threads.getThreadCount());
        reg.gauge("jvm.threads.peak", "Peak thread count", "{thread}", Collections.emptyMap()).set(threads.getPeakThreadCount());
        reg.gauge("jvm.threads.daemon", "Daemon thread count", "{thread}", Collections.emptyMap()).set(threads.getDaemonThreadCount());

        // Thread state breakdown (OTel: jvm.thread.count with state tag)
        try {
            long[] ids = threads.getAllThreadIds();
            ThreadInfo[] infos = threads.getThreadInfo(ids); // maxDepth=0 → no stack trace
            Map<Thread.State, Long> stateCounts = new EnumMap<>(Thread.State.class);
            for (ThreadInfo ti : infos) {
                if (ti != null) stateCounts.merge(ti.getThreadState(), 1L, Long::sum);
            }
            stateCounts.forEach((state, count) -> {
                Map<String, String> tags = attr("state", state.name().toLowerCase());
                reg.gauge("jvm.thread.count", "Thread count by state", "{thread}", tags).set(count);
            });
        } catch (Throwable ignored) {}
    }

    private static void collectClasses() {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        MetricRegistry reg = MetricRegistry.get();
        reg.gauge("jvm.classes.loaded", "Total classes loaded", "1", Collections.emptyMap()).set(cl.getLoadedClassCount());
        reg.gauge("jvm.classes.unloaded", "Total classes unloaded", "1", Collections.emptyMap()).set(cl.getUnloadedClassCount());
    }

    private static void collectCpu() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

            java.lang.reflect.Method processM = PROCESS_CPU_LOAD_METHOD;
            if (processM == null) {
                processM = os.getClass().getDeclaredMethod("getProcessCpuLoad");
                processM.setAccessible(true);
                PROCESS_CPU_LOAD_METHOD = processM;
            }
            double cpuLoad = (double) processM.invoke(os);
            if (cpuLoad >= 0) {
                MetricRegistry.get().gauge("process.cpu.utilization", "Process CPU utilization (0-100 percent)", "%", Collections.emptyMap())
                        .set((long) (cpuLoad * 100));
            }

            // 시스템 전체 CPU — JDK 14+ getCpuLoad, 이하 getSystemCpuLoad (첫 호출에만 탐색)
            java.lang.reflect.Method sysM = SYSTEM_CPU_LOAD_METHOD;
            if (sysM == null) {
                try {
                    sysM = os.getClass().getDeclaredMethod("getCpuLoad");
                } catch (NoSuchMethodException e) {
                    sysM = os.getClass().getDeclaredMethod("getSystemCpuLoad");
                }
                sysM.setAccessible(true);
                SYSTEM_CPU_LOAD_METHOD = sysM;
            }
            double systemLoad = (double) sysM.invoke(os);
            if (systemLoad >= 0) {
                MetricRegistry.get().gauge("system.cpu.utilization", "System CPU utilization (0-100 percent)", "%", Collections.emptyMap())
                        .set((long) (systemLoad * 100));
            }
        } catch (Throwable ignored) {}
    }

    private static void collectSystemMemory() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

            java.lang.reflect.Method freeM = FREE_PHYSICAL_MEMORY_METHOD;
            if (freeM == null) {
                freeM = os.getClass().getDeclaredMethod("getFreePhysicalMemorySize");
                freeM.setAccessible(true);
                FREE_PHYSICAL_MEMORY_METHOD = freeM;
            }
            long free = (long) freeM.invoke(os);

            java.lang.reflect.Method totalM = TOTAL_PHYSICAL_MEMORY_METHOD;
            if (totalM == null) {
                totalM = os.getClass().getDeclaredMethod("getTotalPhysicalMemorySize");
                totalM.setAccessible(true);
                TOTAL_PHYSICAL_MEMORY_METHOD = totalM;
            }
            long total = (long) totalM.invoke(os);

            MetricRegistry reg = MetricRegistry.get();
            reg.gauge("system.memory.free", "Free physical memory", "By", Collections.emptyMap()).set(free);
            reg.gauge("system.memory.total", "Total physical memory", "By", Collections.emptyMap()).set(total);
        } catch (Throwable ignored) {}
    }

    private static void collectBufferPools() {
        try {
            List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            MetricRegistry reg = MetricRegistry.get();
            for (BufferPoolMXBean bp : pools) {
                Map<String, String> tags = attr("pool", bp.getName()); // "direct" or "mapped"
                reg.gauge("jvm.buffer.count", "Buffer pool instance count", "{buffer}", tags)
                        .set(bp.getCount());
                long memUsed = bp.getMemoryUsed();
                if (memUsed >= 0) {
                    reg.gauge("jvm.buffer.memory.usage", "Buffer pool used memory", "By", tags)
                            .set(memUsed);
                }
                long capacity = bp.getTotalCapacity();
                if (capacity >= 0) {
                    reg.gauge("jvm.buffer.memory.limit", "Buffer pool total capacity", "By", tags)
                            .set(capacity);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void collectFileDescriptors() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.UnixOperatingSystemMXBean) {
                com.sun.management.UnixOperatingSystemMXBean unix =
                        (com.sun.management.UnixOperatingSystemMXBean) os;
                MetricRegistry reg = MetricRegistry.get();
                reg.gauge("process.open_file_descriptors", "Open file descriptor count", "{fd}", Collections.emptyMap())
                        .set(unix.getOpenFileDescriptorCount());
                reg.gauge("process.max_file_descriptors", "Max file descriptor count", "{fd}", Collections.emptyMap())
                        .set(unix.getMaxFileDescriptorCount());
            }
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> attr(String key, String value) {
        Map<String, String> m = new HashMap<>(2);
        m.put(key, value);
        return m;
    }
}
