package com.agent.trace;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.common.utils.time.Clock;
import com.agent.sampler.Sampler;
import com.agent.span.SpanLimits;
import com.agent.trace.exporter.SpanExporter;
import com.agent.trace.processor.BatchSpanProcessor;
import com.agent.trace.processor.SpanProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * SdkTracerProvider 생성을 위한 빌더.
 *
 * <pre>{@code
 * SdkTracerProvider provider = SdkTracerProvider.builder()
 *     .setSampler(Sampler.traceIdRatioBased(0.1))
 *     .setSpanLimits(new SpanLimits(256, 256, 256))
 *     .addBatchSpanProcessor(exporter, 2048, 512, 5000)
 *     .build();
 * }</pre>
 */
public final class SdkTracerProviderBuilder {
    private Clock clock = Clock.system();
    private IdGenerator idGenerator = IdGenerator.random();
    private TracerConfig tracerConfig = TracerConfig.defaultConfig();
    private SpanLimits spanLimits = SpanLimits.defaultLimits();
    private Sampler sampler = Sampler.alwaysOn();
    private final List<SpanProcessor> spanProcessors = new ArrayList<>();

    SdkTracerProviderBuilder() {}

    public SdkTracerProviderBuilder setClock(Clock clock) {
        this.clock = clock == null ? Clock.system() : clock;
        return this;
    }

    public SdkTracerProviderBuilder setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator == null ? IdGenerator.random() : idGenerator;
        return this;
    }

    public SdkTracerProviderBuilder setTracerConfig(TracerConfig tracerConfig) {
        this.tracerConfig = tracerConfig == null ? TracerConfig.defaultConfig() : tracerConfig;
        return this;
    }

    public SdkTracerProviderBuilder setSpanLimits(SpanLimits spanLimits) {
        this.spanLimits = spanLimits == null ? SpanLimits.defaultLimits() : spanLimits;
        return this;
    }

    public SdkTracerProviderBuilder setSampler(Sampler sampler) {
        this.sampler = sampler == null ? Sampler.alwaysOn() : sampler;
        return this;
    }

    public SdkTracerProviderBuilder addSpanProcessor(SpanProcessor processor) {
        if (processor != null) {
            this.spanProcessors.add(processor);
        }
        return this;
    }

    public SdkTracerProviderBuilder addBatchSpanProcessor(
            SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        return addSpanProcessor(new BatchSpanProcessor(exporter, maxQueueSize, maxBatchSize, delayMillis));
    }

    public SdkTracerProvider build() {
        return new SdkTracerProvider(clock, idGenerator, tracerConfig, spanLimits, sampler, spanProcessors);
    }
}
