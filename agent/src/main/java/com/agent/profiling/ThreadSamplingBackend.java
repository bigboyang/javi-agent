package com.agent.profiling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

final class ThreadSamplingBackend implements ProfilerBackend {

    private final ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();

    @Override
    public String name() {
        return "thread-sampling";
    }

    @Override
    public Map<String, Integer> collectCpuStacks(long durationMs, long sampleIntervalMs) {
        Map<String, Integer> stackCounts = new HashMap<>(512);
        long deadline = System.currentTimeMillis() + durationMs;

        while (System.currentTimeMillis() < deadline) {
            ThreadInfo[] infos = threadMX.dumpAllThreads(false, false);
            for (ThreadInfo ti : infos) {
                if (isAgentThread(ti.getThreadName())) continue;
                if (ti.getThreadState() != Thread.State.RUNNABLE) continue;
                StackTraceElement[] frames = ti.getStackTrace();
                if (frames == null || frames.length == 0) continue;
                stackCounts.merge(buildCollapsedStack(frames), 1, Integer::sum);
            }
            try {
                Thread.sleep(sampleIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return stackCounts;
    }

    private String buildCollapsedStack(StackTraceElement[] frames) {
        StringBuilder sb = new StringBuilder(frames.length * 64);
        for (int i = frames.length - 1; i >= 0; i--) {
            StackTraceElement frame = frames[i];
            if (i < frames.length - 1) sb.append(';');
            sb.append(frame.getClassName()).append('.').append(frame.getMethodName());
        }
        return sb.toString();
    }

    static boolean isAgentThread(String name) {
        if (name == null) return true;
        return name.startsWith("javi-")
            || name.startsWith("GC ")
            || name.startsWith("G1 ")
            || name.startsWith("Finalizer")
            || name.startsWith("Reference Handler")
            || name.startsWith("Signal Dispatcher")
            || name.startsWith("Attach Listener")
            || name.startsWith("VM Thread")
            || name.startsWith("VM Periodic")
            || name.startsWith("C1 ")
            || name.startsWith("C2 ")
            || name.startsWith("Common-Cleaner");
    }
}
