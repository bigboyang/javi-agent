package com.agent.trace;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.common.utils.time.Clock;
import com.agent.sampler.Sampler;
import com.agent.span.SpanLimits;
import com.agent.trace.processor.NoopSpanProcessor;
import com.agent.trace.processor.SpanProcessor;

/**
 * SDK 전역에서 공유하는 상태.
 */
public final class TracerSharedState {
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SpanLimits spanLimits;
    private volatile SpanProcessor spanProcessor;
    private volatile Sampler sampler;
    private volatile boolean shutdown;

    public TracerSharedState(
            Clock clock,
            IdGenerator idGenerator,
            SpanLimits spanLimits,
            SpanProcessor spanProcessor) {
        this(clock, idGenerator, spanLimits, spanProcessor, Sampler.alwaysOn());
    }

    public TracerSharedState(
            Clock clock,
            IdGenerator idGenerator,
            SpanLimits spanLimits,
            SpanProcessor spanProcessor,
            Sampler sampler) {
        this.clock = clock == null ? Clock.system() : clock;
        this.idGenerator = idGenerator;
        this.spanLimits = spanLimits;
        this.spanProcessor = spanProcessor == null ? NoopSpanProcessor.getInstance() : spanProcessor;
        this.sampler = sampler == null ? Sampler.alwaysOn() : sampler;
        this.shutdown = false;
    }

    public IdGenerator getIdGenerator() {
        return idGenerator;
    }

    public Clock getClock() {
        return clock;
    }

    public SpanLimits getSpanLimits() {
        return spanLimits;
    }

    public SpanProcessor getSpanProcessor() {
        return spanProcessor;
    }

    public void setSpanProcessor(SpanProcessor spanProcessor) {
        this.spanProcessor = spanProcessor == null ? NoopSpanProcessor.getInstance() : spanProcessor;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public Sampler getSampler() {
        return sampler;
    }

    public void setSampler(Sampler sampler) {
        this.sampler = sampler == null ? Sampler.alwaysOn() : sampler;
    }

    public void shutdown() {
        this.shutdown = true;
    }
}
