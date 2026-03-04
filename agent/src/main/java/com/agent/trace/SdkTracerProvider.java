package com.agent.trace;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.common.utils.time.Clock;
import com.agent.span.SpanLimits;
import com.agent.trace.processor.NoopSpanProcessor;
import com.agent.trace.processor.BatchSpanProcessor;
import com.agent.trace.processor.CompositeSpanProcessor;
import com.agent.trace.processor.SimpleSpanProcessor;
import com.agent.trace.processor.SpanProcessor;
import com.agent.trace.exporter.SpanExporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * SDK 기본 TracerProvider 구현체.
 */
public class SdkTracerProvider implements TracerProvider {
    private final TracerSharedState sharedState;
    private volatile TracerConfig tracerConfig;
    private final List<SpanProcessor> spanProcessors = new ArrayList<>();

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

    public void addSimpleSpanProcessor(SpanExporter exporter) {
        addSpanProcessor(new SimpleSpanProcessor(exporter));
    }

    public void addBatchSpanProcessor(SpanExporter exporter, int maxQueueSize, int maxBatchSize, long delayMillis) {
        addSpanProcessor(new BatchSpanProcessor(exporter, maxQueueSize, maxBatchSize, delayMillis));
    }

    public boolean isShutdown() {
        return sharedState.isShutdown();
    }

    public CompletableResultCode shutdown() {
        CompletableResultCode result = sharedState.getSpanProcessor().shutdown();
        sharedState.shutdown();
        return result;
    }

    public CompletableResultCode forceFlush() {
        return sharedState.getSpanProcessor().forceFlush();
    }


    public void setTracerConfig(TracerConfig tracerConfig) {
        this.tracerConfig = tracerConfig;
    }

    @Override
    public Tracer getTracer(String instrumentationName) {
        if (!tracerConfig.isEnabled()) {
            return NoopTracer.INSTANCE;
        }
        InstrumentationScopeInfo info = new InstrumentationScopeInfo(instrumentationName, null, null);
        return new SdkTracer(sharedState, info, tracerConfig);
    }

    @Override
    public TracerBuilder tracerBuilder(String instrumentationName) {
        return new SdkTracerBuilder(this, instrumentationName);
    }
}
