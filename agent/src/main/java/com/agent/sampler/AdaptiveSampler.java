package com.agent.sampler;

import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 적응형 샘플링(Adaptive Sampling).
 * 설정된 초당 목표 스팬 수(Target SPS)를 유지하도록 확률을 동적으로 조정합니다.
 * criticalUrls에 해당하는 스팬은 항상 샘플링되며,
 * clusterMinSamples 이상의 관측 후에만 비율을 조정합니다.
 */
public final class AdaptiveSampler implements Sampler {
    private volatile long targetSps;
    private final AtomicLong spanCount = new AtomicLong(0);
    private volatile double currentRate;
    private volatile long idUpperBound;
    private final List<String> criticalUrls;
    private final int clusterMinSamples;

    public AdaptiveSampler(long targetSps, double initialRate) {
        this(targetSps, initialRate, Collections.emptyList(), 0);
    }

    public AdaptiveSampler(long targetSps, double initialRate, List<String> criticalUrls, int clusterMinSamples) {
        this.targetSps = targetSps;
        this.criticalUrls = criticalUrls == null ? Collections.emptyList() : criticalUrls;
        this.clusterMinSamples = Math.max(0, clusterMinSamples);
        updateRate(initialRate);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-adaptive-sampler");
            t.setDaemon(true);
            return t;
        });

        // 1초마다 샘플링 비율 조정
        scheduler.scheduleAtFixedRate(this::adjustRate, 1, 1, TimeUnit.SECONDS);
    }

    private void adjustRate() {
        long count = spanCount.getAndSet(0);

        // clusterMinSamples 미충족 시 충분한 데이터가 없으므로 비율 조정 생략
        if (clusterMinSamples > 0 && count < clusterMinSamples) {
            return;
        }

        // 현재 처리량이 목표보다 높으면 비율 감소, 낮으면 증가
        double newRate;
        if (count == 0) {
            newRate = Math.min(1.0, currentRate * 1.1); // 조금씩 증가
        } else {
            // (목표 / 실제) 비율로 조정하되, 급격한 변화를 방지하기 위해 가중치 적용
            double ratio = (double) targetSps / count;
            newRate = currentRate * (0.8 + 0.2 * ratio);
        }

        // 범위 제한 (0.01% ~ 100%)
        newRate = Math.max(0.0001, Math.min(1.0, newRate));
        updateRate(newRate);
    }

    private void updateRate(double rate) {
        this.currentRate = rate;
        if (rate <= 0.0) {
            this.idUpperBound = Long.MIN_VALUE;
        } else if (rate >= 1.0) {
            this.idUpperBound = Long.MAX_VALUE;
        } else {
            this.idUpperBound = (long) (rate * Long.MAX_VALUE);
        }
    }

    @Override
    public SamplingDecision shouldSample(SpanContext parentContext, String traceId, String spanName, SpanKind spanKind) {
        // OTel standard: honor parent sampling decision for non-root spans
        if (parentContext != null && parentContext.isValid()) {
            return parentContext.isSampled() ? SamplingDecision.RECORD_AND_SAMPLE : SamplingDecision.DROP;
        }

        // criticalUrls에 해당하는 스팬은 항상 샘플링
        if (spanName != null && isCritical(spanName)) {
            return SamplingDecision.RECORD_AND_SAMPLE;
        }

        spanCount.incrementAndGet();

        if (traceId == null || traceId.length() < 16) {
            return SamplingDecision.DROP;
        }

        try {
            long low = Long.parseUnsignedLong(traceId.substring(traceId.length() - 16), 16);
            // Unsigned comparison: treats long as [0, 2^64) not [-2^63, 2^63)
            return Long.compareUnsigned(low, idUpperBound) <= 0
                    ? SamplingDecision.RECORD_AND_SAMPLE : SamplingDecision.DROP;
        } catch (NumberFormatException e) {
            return SamplingDecision.RECORD_AND_SAMPLE;
        }
    }

    public double getCurrentRate() {
        return currentRate;
    }

    private boolean isCritical(String spanName) {
        for (String url : criticalUrls) {
            if (spanName.contains(url)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 원격 설정 변경 시 targetTps와 초기 샘플링 비율을 동적으로 업데이트한다.
     *
     * @param newTargetTps 새 목표 TPS (0 이하면 현재 유지)
     * @param newRate      새 샘플링 비율 (0.0 ~ 1.0)
     */
    public void update(long newTargetTps, double newRate) {
        if (newTargetTps > 0) {
            this.targetSps = newTargetTps;
        }
        updateRate(Math.max(0.0, Math.min(1.0, newRate)));
    }
}
