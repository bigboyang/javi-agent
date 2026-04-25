package com.agent.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원격 설정 스냅샷 (불변 객체).
 *
 * 서버에서 폴링한 설정을 원자적으로 교체하기 위해 불변 설계.
 * RemoteConfigHolder가 volatile 참조로 최신 스냅샷을 보유한다.
 */
public final class RemoteConfig {

    // ── Sampling ─────────────────────────────────────────────────────────────
    /** Head Sampling Rate (0.0 ~ 1.0). 0 = all drop, 1.0 = all sample */
    private final double headSampleRate;

    /** 서비스별 가중치. weight > 1.0 → 더 많이 샘플, < 1.0 → 더 적게 샘플 */
    private final Map<String, Double> serviceWeight;

    /** AdaptiveSampler 목표 TPS. 0 = 비활성 */
    private final long targetTps;

    // ── Instrumentation Control ───────────────────────────────────────────────
    /** MDC/ThreadContext traceId/spanId 주입 여부 */
    private final boolean logInjection;

    /** 메트릭 수집 범위: "all" | "jvm_only" | "disabled" */
    private final String metrics;

    /** 스팬 내보낼 때 제거할 속성 키 집합 */
    private final Set<String> spanDrop;

    /** 스팬에 캡처할 HTTP 요청 헤더 목록 */
    private final List<String> customHeaders;

    // ── Emergency Controls ────────────────────────────────────────────────────
    /** true 시 모든 스팬 드롭 (긴급 차단) */
    private final boolean emergencyOff;

    /** 계측을 비활성화할 서비스 이름 집합 */
    private final Set<String> serviceDisable;

    /** 큐가 꽉 찼을 때 드롭(true) vs 블로킹(false) */
    private final boolean dropOnFull;

    // ── Performance Tuning ────────────────────────────────────────────────────
    /** BatchSpanProcessor 배치 크기 */
    private final int batchSize;

    /** BatchSpanProcessor 내보내기 간격 (ms) */
    private final long exportDelay;

    /** OTLP 내보내기 실패 시 재시도 횟수 */
    private final int retryCount;

    private RemoteConfig(
            double headSampleRate,
            Map<String, Double> serviceWeight, long targetTps,
            boolean logInjection, String metrics,
            Set<String> spanDrop, List<String> customHeaders,
            boolean emergencyOff, Set<String> serviceDisable, boolean dropOnFull,
            int batchSize, long exportDelay, int retryCount) {
        this.headSampleRate = headSampleRate;
        this.serviceWeight = Collections.unmodifiableMap(serviceWeight);
        this.targetTps = targetTps;
        this.logInjection = logInjection;
        this.metrics = metrics;
        this.spanDrop = Collections.unmodifiableSet(spanDrop);
        this.customHeaders = Collections.unmodifiableList(customHeaders);
        this.emergencyOff = emergencyOff;
        this.serviceDisable = Collections.unmodifiableSet(serviceDisable);
        this.dropOnFull = dropOnFull;
        this.batchSize = batchSize;
        this.exportDelay = exportDelay;
        this.retryCount = retryCount;
    }

    /** 기본값으로 생성 (원격 설정 없을 때 사용). AgentConfig의 로컬 설정을 기본 베이스로 삼는다. */
    public static RemoteConfig defaults() {
        AgentConfig local = AgentConfig.get();

        return new RemoteConfig(
                local.getSampleRate(), Collections.emptyMap(), local.getTargetSps(),
                true, "all", Collections.emptySet(), Collections.emptyList(),
                false, Collections.emptySet(), true,
                512, 5000L, 3
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public double getHeadSampleRate() { return headSampleRate; }
    public Map<String, Double> getServiceWeight() { return serviceWeight; }
    public long getTargetTps() { return targetTps; }
    public boolean isLogInjection() { return logInjection; }
    public String getMetrics() { return metrics; }
    public Set<String> getSpanDrop() { return spanDrop; }
    public List<String> getCustomHeaders() { return customHeaders; }
    public boolean isEmergencyOff() { return emergencyOff; }
    public Set<String> getServiceDisable() { return serviceDisable; }
    public boolean isDropOnFull() { return dropOnFull; }
    public int getBatchSize() { return batchSize; }
    public long getExportDelay() { return exportDelay; }
    public int getRetryCount() { return retryCount; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private double headSampleRate = 1.0;
        private Map<String, Double> serviceWeight = Collections.emptyMap();
        private long targetTps = 0L;
        private boolean logInjection = true;
        private String metrics = "all";
        private Set<String> spanDrop = Collections.emptySet();
        private List<String> customHeaders = Collections.emptyList();
        private boolean emergencyOff = false;
        private Set<String> serviceDisable = Collections.emptySet();
        private boolean dropOnFull = true;
        private int batchSize = 512;
        private long exportDelay = 5000L;
        private int retryCount = 3;

        public Builder headSampleRate(double v) { this.headSampleRate = v; return this; }
        public Builder serviceWeight(Map<String, Double> v) { this.serviceWeight = v; return this; }
        public Builder targetTps(long v) { this.targetTps = v; return this; }
        public Builder logInjection(boolean v) { this.logInjection = v; return this; }
        public Builder metrics(String v) { this.metrics = v; return this; }
        public Builder spanDrop(Set<String> v) { this.spanDrop = v; return this; }
        public Builder customHeaders(List<String> v) { this.customHeaders = v; return this; }
        public Builder emergencyOff(boolean v) { this.emergencyOff = v; return this; }
        public Builder serviceDisable(Set<String> v) { this.serviceDisable = v; return this; }
        public Builder dropOnFull(boolean v) { this.dropOnFull = v; return this; }
        public Builder batchSize(int v) { this.batchSize = v; return this; }
        public Builder exportDelay(long v) { this.exportDelay = v; return this; }
        public Builder retryCount(int v) { this.retryCount = v; return this; }

        public RemoteConfig build() {
            return new RemoteConfig(
                    headSampleRate, serviceWeight, targetTps,
                    logInjection, metrics, spanDrop, customHeaders,
                    emergencyOff, serviceDisable, dropOnFull,
                    batchSize, exportDelay, retryCount
            );
        }
    }
}
