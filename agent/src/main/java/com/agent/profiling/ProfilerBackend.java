package com.agent.profiling;

import java.util.Map;

interface ProfilerBackend {
    String name();
    Map<String, Integer> collectCpuStacks(long durationMs, long sampleIntervalMs);
}
