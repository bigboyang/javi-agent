package com.agent.profiling;

import com.agent.logs.AgentLogger;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * async-profiler 기반 CPU 프로파일러 백엔드 (선택적).
 *
 * <p>async-profiler(https://github.com/async-profiler/async-profiler) JAR이
 * 클래스패스에 존재하거나, {@code -agentpath:libasyncProfiler.so}로 네이티브 라이브러리가
 * 미리 로드된 환경에서만 동작한다.
 * 감지 실패 시 {@link ProfilingExporter}가 자동으로 {@link JfrProfilerBackend}로 폴백한다.
 *
 * <p>장점:
 * <ul>
 *   <li>Linux perf_events 기반 — I/O 대기 중 네이티브 프레임까지 캡처</li>
 *   <li>JVM safepoint 편향 없음 — 진정한 CPU-bound 스택 샘플링</li>
 *   <li>Allocation/Lock 프로파일링 지원 (확장 가능)</li>
 * </ul>
 *
 * <p>활성화 방법:
 * <pre>
 *   # 1) async-profiler JAR을 에이전트 클래스패스에 추가
 *   java -javaagent:javi-agent.jar -cp async-profiler.jar ...
 *
 *   # 2) 또는 네이티브 라이브러리를 먼저 로드
 *   java -agentpath:/path/to/libasyncProfiler.so -javaagent:javi-agent.jar ...
 * </pre>
 */
final class AsyncProfilerBackend implements ProfilerBackend {

    private static final String AP_CLASS = "one.profiler.AsyncProfiler";

    private final Object instance;
    private final Method executeMethod;

    private AsyncProfilerBackend(Object instance, Method executeMethod) {
        this.instance = instance;
        this.executeMethod = executeMethod;
    }

    /**
     * async-profiler가 사용 가능한 환경이면 인스턴스를 반환, 아니면 null을 반환한다.
     * 반환값이 null이면 호출자가 다음 백엔드로 폴백한다.
     */
    static AsyncProfilerBackend tryCreate() {
        try {
            Class<?> cls = Class.forName(AP_CLASS);
            Method getInstance = cls.getMethod("getInstance");
            Object inst = getInstance.invoke(null);
            Method execute = cls.getMethod("execute", String.class);
            AgentLogger.info("[profiling] async-profiler 감지 — async-profiler 백엔드 사용");
            return new AsyncProfilerBackend(inst, execute);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String name() {
        return "async-profiler";
    }

    /**
     * async-profiler string command API를 통해 CPU 샘플을 수집한다.
     *
     * <p>버전 독립성을 위해 {@code execute(String)} 문자열 명령 API를 사용한다.
     * 반환되는 collapsed 포맷은 {@code "frame1;frame2;...;frameN count"} 형식이며
     * 기존 Flame Graph 파이프라인과 바로 호환된다.
     */
    @Override
    public Map<String, Integer> collectCpuStacks(long durationMs, long sampleIntervalMs) {
        try {
            String startCmd = "start,event=cpu,interval=" + sampleIntervalMs + "ms,threads";
            executeMethod.invoke(instance, startCmd);

            Thread.sleep(durationMs);

            String collapsed = (String) executeMethod.invoke(instance, "stop,collapsed");
            return parseCollapsed(collapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeStop();
            return new HashMap<>();
        } catch (Throwable t) {
            AgentLogger.warn("[profiling][async-profiler] 수집 실패: " + t.getMessage());
            safeStop();
            return new HashMap<>();
        }
    }

    private void safeStop() {
        try {
            executeMethod.invoke(instance, "stop");
        } catch (Exception ignored) {}
    }

    /**
     * async-profiler collapsed 포맷 파싱.
     * 각 줄: {@code "frame1;frame2;...;frameN count"}
     */
    private Map<String, Integer> parseCollapsed(String collapsed) {
        if (collapsed == null || collapsed.isEmpty()) return new HashMap<>();
        Map<String, Integer> counts = new HashMap<>(512);
        try (BufferedReader br = new BufferedReader(new StringReader(collapsed))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int lastSpace = line.lastIndexOf(' ');
                if (lastSpace < 0) continue;
                try {
                    String stack = line.substring(0, lastSpace);
                    int count = Integer.parseInt(line.substring(lastSpace + 1));
                    counts.put(stack, count);
                } catch (NumberFormatException ignored) {}
            }
        } catch (java.io.IOException ignored) {}
        return counts;
    }
}
