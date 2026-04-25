package com.agent.profiling;

import com.agent.logs.AgentLogger;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK Flight Recorder 기반 CPU + Allocation 프로파일러 백엔드.
 *
 * <p>수집 이벤트:
 * <ul>
 *   <li>{@code jdk.ExecutionSample} — CPU 프로파일링 (RUNNABLE 스레드)</li>
 *   <li>{@code jdk.ObjectAllocationInNewTLAB} — TLAB 내 객체 할당 샘플링</li>
 *   <li>{@code jdk.ObjectAllocationOutsideTLAB} — TLAB 외부 대형 객체 할당</li>
 * </ul>
 *
 * <p>Allocation 스택은 맵 키를 {@code "alloc:"} 접두어로 구분한다.
 * {@link ProfilingExporter}가 export 시 profile_type별로 분리 전송한다.
 *
 * <p>JDK 11+ 환경에서 {@link FlightRecorder#isAvailable()}이 {@code true}일 때만 동작한다.
 */
final class JfrProfilerBackend implements ProfilerBackend {

    private static final String CPU_EVENT    = "jdk.ExecutionSample";
    private static final String ALLOC_TLAB   = "jdk.ObjectAllocationInNewTLAB";
    private static final String ALLOC_NOTLAB = "jdk.ObjectAllocationOutsideTLAB";

    static final String ALLOC_PREFIX = "alloc:";

    private final Path jfrTmpFile;

    JfrProfilerBackend() {
        try {
            this.jfrTmpFile = Files.createTempFile("javi-cpu-", ".jfr");
            this.jfrTmpFile.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException("JFR temp file 생성 실패", e);
        }
    }

    static boolean isAvailable() {
        try {
            return FlightRecorder.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String name() {
        return "jfr";
    }

    @Override
    public Map<String, Integer> collectCpuStacks(long durationMs, long sampleIntervalMs) {
        try (Recording rec = new Recording()) {
            rec.enable(CPU_EVENT)
               .withPeriod(Duration.ofMillis(Math.max(sampleIntervalMs, 1)));
            rec.enable(ALLOC_TLAB);
            rec.enable(ALLOC_NOTLAB);
            rec.start();
            Thread.sleep(durationMs);
            rec.stop();
            rec.dump(jfrTmpFile);
            return parseStackCounts(jfrTmpFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HashMap<>();
        } catch (Throwable t) {
            AgentLogger.warn("[profiling][jfr] 수집 실패: " + t.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, Integer> parseStackCounts(Path jfrFile) throws Exception {
        Map<String, Integer> counts = new HashMap<>(512);
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String evtName = event.getEventType().getName();

                if (CPU_EVENT.equals(evtName)) {
                    parseCpuEvent(event, counts);
                } else if (ALLOC_TLAB.equals(evtName) || ALLOC_NOTLAB.equals(evtName)) {
                    parseAllocEvent(event, counts);
                }
            }
        }
        return counts;
    }

    private void parseCpuEvent(RecordedEvent event, Map<String, Integer> counts) {
        try {
            String state = event.getString("state");
            if (state != null && !state.contains("RUNNABLE")) return;
        } catch (Exception ignored) {}

        try {
            RecordedThread thread = event.getThread("sampledThread");
            String threadName = thread != null ? thread.getJavaName() : null;
            if (ThreadSamplingBackend.isAgentThread(threadName)
                    || isJfrInternalThread(threadName)) return;
        } catch (Exception ignored) {}

        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null) return;
        List<RecordedFrame> frames = stackTrace.getFrames();
        if (frames == null || frames.isEmpty()) return;

        String collapsed = buildCollapsedStack(frames);
        if (!collapsed.isEmpty()) {
            counts.merge(collapsed, 1, Integer::sum);
        }
    }

    private void parseAllocEvent(RecordedEvent event, Map<String, Integer> counts) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null) return;
        List<RecordedFrame> frames = stackTrace.getFrames();
        if (frames == null || frames.isEmpty()) return;

        String collapsed = buildCollapsedStack(frames);
        if (!collapsed.isEmpty()) {
            counts.merge(ALLOC_PREFIX + collapsed, 1, Integer::sum);
        }
    }

    private String buildCollapsedStack(List<RecordedFrame> frames) {
        StringBuilder sb = new StringBuilder(frames.size() * 64);
        boolean firstFrame = true;
        for (int i = frames.size() - 1; i >= 0; i--) {
            RecordedMethod method = frames.get(i).getMethod();
            if (method == null) continue;
            if (!firstFrame) sb.append(';');
            firstFrame = false;
            String typeName = (method.getType() != null) ? method.getType().getName() : "unknown";
            sb.append(typeName).append('.').append(method.getName());
        }
        return sb.toString();
    }

    private boolean isJfrInternalThread(String name) {
        if (name == null) return false;
        return name.startsWith("JFR ")
            || name.startsWith("JFR.")
            || name.equals("Periodic task thread");
    }
}
