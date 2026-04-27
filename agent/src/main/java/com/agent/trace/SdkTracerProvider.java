package com.agent.trace;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.common.utils.time.Clock;
import com.agent.sampler.Sampler;
import com.agent.span.SpanLimits;
import com.agent.trace.processor.NoopSpanProcessor;
import com.agent.trace.processor.BatchSpanProcessor;
import com.agent.trace.processor.CompositeSpanProcessor;
import com.agent.trace.processor.SpanProcessor;
import com.agent.trace.exporter.SpanExporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


/**
 * SDK 기본 TracerProvider 구현체.
 */
public class SdkTracerProvider implements TracerProvider {
    private final TracerSharedState sharedState;
    private volatile TracerConfig tracerConfig;
    private final List<SpanProcessor> spanProcessors = new ArrayList<>();
    private final ConcurrentHashMap<InstrumentationScopeInfo, SdkTracer> tracerCache = new ConcurrentHashMap<>();

    public static SdkTracerProviderBuilder builder() {
        return new SdkTracerProviderBuilder();
    }

    public SdkTracerProvider(IdGenerator idGenerator) {
        this(Clock.system(), idGenerator, TracerConfig.defaultConfig(), NoopSpanProcessor.getInstance());
    }

    public SdkTracerProvider(IdGenerator idGenerator, TracerConfig tracerConfig) {
        this(Clock.system(), idGenerator, tracerConfig, NoopSpanProcessor.getInstance());
    }

    public SdkTracerProvider(
            Clock clock,
            IdGenerator idGenerator,
            TracerConfig tracerConfig,
            SpanProcessor spanProcessor) {
        this.sharedState = new TracerSharedState(
                clock,
                idGenerator,
                SpanLimits.defaultLimits(),
                spanProcessor);
        this.tracerConfig = tracerConfig;
        if (spanProcessor != null) {
            spanProcessors.add(spanProcessor);
        }
    }

    /** Builder로부터 호출되는 전체 인수 생성자. */
    SdkTracerProvider(
            Clock clock,
            IdGenerator idGenerator,
            TracerConfig tracerConfig,
            SpanLimits spanLimits,
            Sampler sampler,
            List<SpanProcessor> initialProcessors) {
        SpanProcessor combined = CompositeSpanProcessor.create(initialProcessors);
        this.sharedState = new TracerSharedState(clock, idGenerator, spanLimits, combined, sampler);
        this.tracerConfig = tracerConfig;
        this.spanProcessors.addAll(initialProcessors);
    }

    public IdGenerator getIdGenerator() {
        return sharedState.getIdGenerator();
    }

    public TracerSharedState getSharedState() {
        return sharedState;
    }

    public TracerConfig getTracerConfig() {
        return tracerConfig;
    }

    public SpanLimits getSpanLimits() {
        return sharedState.getSpanLimits();
    }

    public SpanProcessor getSpanProcessor() {
        return sharedState.getSpanProcessor();
    }

    public void setSpanProcessor(SpanProcessor spanProcessor) {
        synchronized (spanProcessors) {
            spanProcessors.clear();
            if (spanProcessor != null) {
                spanProcessors.add(spanProcessor);
            }
            sharedState.setSpanProcessor(CompositeSpanProcessor.create(spanProcessors));
        }
    }

    public void addSpanProcessor(SpanProcessor spanProcessor) {
        if (spanProcessor == null) {
            return;
        }
        synchronized (spanProcessors) {
            spanProcessors.add(spanProcessor);
            sharedState.setSpanProcessor(CompositeSpanProcessor.create(spanProcessors));
        }
    }

    public void setSpanProcessors(List<SpanProcessor> processors) {
        synchronized (spanProcessors) {
            spanProcessors.clear();
            if (processors != null) {
                spanProcessors.addAll(processors);
            }
            sharedState.setSpanProcessor(CompositeSpanProcessor.create(spanProcessors));
        }
    }

    public List<SpanProcessor> getSpanProcessors() {
        synchronized (spanProcessors) {
            return Collections.unmodifiableList(new ArrayList<>(spanProcessors));
        }
    }

    public void addBatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        addSpanProcessor(new BatchSpanProcessor(exporter, maxQueueSize, maxBatchSize, delayMillis));
    }

    public boolean isShutdown() {
        return sharedState.isShutdown();
    }

    @Override
    public CompletableResultCode shutdown() {
        CompletableResultCode result = sharedState.getSpanProcessor().shutdown();
        sharedState.shutdown();
        return result;
    }

    @Override
    public CompletableResultCode forceFlush() {
        return sharedState.getSpanProcessor().forceFlush();
    }

    /**
     * shutdown() 완료까지 최대 {@code timeoutMs} 밀리초 대기한다.
     * JVM Shutdown Hook 등에서 간편하게 사용할 수 있다.
     */
    public void shutdownAndWait(long timeoutMs) {
        try {
            shutdown().join(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }


    public void setSampler(Sampler sampler) {
        sharedState.setSampler(sampler);
    }

    public void setTracerConfig(TracerConfig tracerConfig) {
        this.tracerConfig = tracerConfig;
        // 캐시를 비우는 대신 기존 SdkTracer 인스턴스를 직접 업데이트한다.
        // 이렇게 하면 기존에 획득한 Tracer 레퍼런스도 즉시 새 설정을 반영한다.
        tracerCache.values().forEach(t -> t.updateTracerConfig(tracerConfig));
    }

    @Override
    public Tracer getTracer(String instrumentationName) {
        if (!tracerConfig.isEnabled() || sharedState.isShutdown()) {
            return NoopTracer.INSTANCE;
        }
        InstrumentationScopeInfo info = new InstrumentationScopeInfo(instrumentationName, null, null);
        return tracerCache.computeIfAbsent(info, k -> new SdkTracer(sharedState, k, tracerConfig));
    }

    /** Package-private: used by SdkTracerBuilder to share the tracer cache. */
    SdkTracer getOrCreateTracer(InstrumentationScopeInfo info) {
        return tracerCache.computeIfAbsent(info, k -> new SdkTracer(sharedState, k, tracerConfig));
    }

    @Override
    public TracerBuilder tracerBuilder(String instrumentationName) {
        return new SdkTracerBuilder(this, instrumentationName);
    }
}
