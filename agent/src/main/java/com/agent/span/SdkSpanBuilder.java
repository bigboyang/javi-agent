package com.agent.span;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.common.utils.time.AnchoredClock;
import com.agent.common.utils.time.Clock;
import com.agent.sampler.SamplingDecision;
import com.agent.trace.InstrumentationScopeInfo;
import com.agent.trace.TracerSharedState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK 기본 SpanBuilder 구현체.
 */
public final class SdkSpanBuilder implements SpanBuilder {
    private final String name;
    private final TracerSharedState sharedState;
    private final InstrumentationScopeInfo instrumentationScopeInfo;
    private final boolean tracerEnabled;
    private SpanContext parent;
    private final List<SpanLink> links = new ArrayList<>();
    private SpanKind spanKind = SpanKind.INTERNAL;
    private long startTimestampNanos;
    private final Clock clock;
    private final Map<AttributeKey<?>, Object> startAttributes = new LinkedHashMap<>();

    public SdkSpanBuilder(
            String name,
            TracerSharedState sharedState,
            InstrumentationScopeInfo instrumentationScopeInfo,
            boolean tracerEnabled) {
        this.name = name;
        this.sharedState = sharedState;
        this.instrumentationScopeInfo = instrumentationScopeInfo;
        this.tracerEnabled = tracerEnabled;
        this.parent = Context.currentSpan().getContext();
        this.clock = sharedState.getClock();
    }

    @Override
    public SpanBuilder setParent(SpanContext parent) {
        this.parent = parent;
        return this;
    }

    @Override
    public SpanBuilder setNoParent() {
        this.parent = null;
        return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext) {
        return addLink(spanContext, null);
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext, Map<AttributeKey<?>, Object> attributes) {
        if (spanContext == null || !spanContext.isValid()) {
            return this;
        }
        if (links.size() >= sharedState.getSpanLimits().getMaxLinks()) {
            sharedState.getSpanLimits().recordDroppedLink();
            return this;
        }
        links.add(new SpanLink(spanContext, attributes));
        return this;
    }

    @Override
    public SpanBuilder setSpanKind(SpanKind spanKind) {
        this.spanKind = spanKind == null ? SpanKind.INTERNAL : spanKind;
        return this;
    }

    @Override
    public SpanBuilder setStartTimestamp(long startTimestampNanos) {
        if (startTimestampNanos > 0) {
            this.startTimestampNanos = startTimestampNanos;
        }
        return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, String value) {
        return setAttribute(AttributeKey.stringKey(key), value);
    }

    @Override
    public SpanBuilder setAttribute(String key, long value) {
        return setAttribute(AttributeKey.longKey(key), value);
    }

    @Override
    public SpanBuilder setAttribute(String key, double value) {
        return setAttribute(AttributeKey.doubleKey(key), value);
    }

    @Override
    public SpanBuilder setAttribute(String key, boolean value) {
        return setAttribute(AttributeKey.booleanKey(key), value);
    }

    @Override
    public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
        if (key != null && value != null) {
            startAttributes.put(key, value);
        }
        return this;
    }

    @Override
    public Span startSpan() {
        if (!tracerEnabled || sharedState.isShutdown()) {
            return Span.invalid();
        }
        IdGenerator idGenerator = sharedState.getIdGenerator();
        String traceId = parent != null && parent.isValid()
                ? parent.getTraceId()
                : idGenerator.generateTraceId();
        String parentSpanId = parent != null && parent.isValid()
                ? parent.getSpanId()
                : SpanId.getInvalid();
        String spanId = idGenerator.generateSpanId();

        SamplingDecision decision = sharedState.getSampler()
                .shouldSample(parent, traceId, name, spanKind);
        if (decision == SamplingDecision.DROP) {
            return Span.invalid();
        }
        SpanContext context = SpanContext.create(traceId, spanId, parentSpanId, true);
        AnchoredClock anchoredClock = resolveAnchoredClock();
        long start = startTimestampNanos > 0 ? startTimestampNanos : anchoredClock.now();
        long startNanoTime = clock.nanoTime();
        SdkSpan span = new SdkSpan(
                name,
                context,
                instrumentationScopeInfo,
                start,
                links,
                sharedState.getSpanLimits(),
                spanKind,
                sharedState.getSpanProcessor(),
                anchoredClock,
                clock,
                startNanoTime);
        // apply start attributes
        for (Map.Entry<AttributeKey<?>, Object> entry : startAttributes.entrySet()) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) entry.getKey();
            span.setAttribute(key, entry.getValue());
        }
        sharedState.getSpanProcessor().onStart(span);
        return span;
    }

    private AnchoredClock resolveAnchoredClock() {
        Span current = Context.currentSpan();
        if (current instanceof SdkSpan) {
            return ((SdkSpan) current).getAnchoredClock();
        }
        if (current instanceof PropagatedSpan) {
            return ((PropagatedSpan) current).getAnchoredClock();
        }
        return AnchoredClock.create(clock);
    }
}
