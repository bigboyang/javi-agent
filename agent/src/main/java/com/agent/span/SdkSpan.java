package com.agent.span;

import com.agent.common.utils.time.AnchoredClock;
import com.agent.common.utils.time.Clock;
import com.agent.trace.processor.SpanProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK 기본 스팬 구현체.
 */
final class SdkSpan implements Span {
    private final String name;
    private final SpanContext context;
    private final long startTimeNanos;
    private final List<SpanLink> links;
    private final SpanLimits spanLimits;
    private final SpanKind spanKind;
    private final SpanProcessor spanProcessor;
    private final AnchoredClock anchoredClock;
    private final Clock clock;
    private final long startNanoTime;
    private final Map<AttributeKey<?>, Object> attributes = new LinkedHashMap<>();
    private final List<SpanEvent> events = new ArrayList<>();
    private SpanStatus status = SpanStatus.UNSET;
    private String statusDescription;
    private Throwable recordedException;
    private long endTimeNanos;
    private long endNanoTime;

    SdkSpan(
            String name,
            SpanContext context,
            long startTimeNanos,
            List<SpanLink> links,
            SpanLimits spanLimits,
            SpanKind spanKind,
            SpanProcessor spanProcessor,
            AnchoredClock anchoredClock,
            Clock clock,
            long startNanoTime) {
        this.name = name;
        this.context = context;
        this.startTimeNanos = startTimeNanos;
        this.links = links == null ? Collections.emptyList() : new ArrayList<>(links);
        this.spanLimits = spanLimits;
        this.spanKind = spanKind;
        this.spanProcessor = spanProcessor;
        this.anchoredClock = anchoredClock;
        this.clock = clock;
        this.startNanoTime = startNanoTime;
    }

    @Override
    public Scope makeCurrent() {
        return Context.makeCurrent(this);
    }

    @Override
    public void end() {
        if (endTimeNanos != 0) {
            return;
        }
        endTimeNanos = anchoredClock.now();
        endNanoTime = clock.nanoTime();
        if (spanProcessor != null) {
            spanProcessor.onEnd(this);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public SpanContext getContext() {
        return context;
    }

    @Override
    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    @Override
    public long getEndTimeNanos() {
        return endTimeNanos;
    }

    long getDurationNanos() {
        if (endNanoTime == 0) {
            return 0;
        }
        return endNanoTime - startNanoTime;
    }

    @Override
    public SpanKind getKind() {
        return spanKind;
    }

    @Override
    public Span setAttribute(String key, String value) {
        return setAttribute(AttributeKey.stringKey(key), value);
    }

    @Override
    public Span setAttribute(String key, long value) {
        return setAttribute(AttributeKey.longKey(key), value);
    }

    @Override
    public Span setAttribute(String key, double value) {
        return setAttribute(AttributeKey.doubleKey(key), value);
    }

    @Override
    public Span setAttribute(String key, boolean value) {
        return setAttribute(AttributeKey.booleanKey(key), value);
    }

    public <T> Span setAttribute(AttributeKey<T> key, T value) {
        if (key == null) {
            return this;
        }
        if (attributes.size() >= spanLimits.getMaxAttributes()) {
            spanLimits.recordDroppedAttribute();
            SpanLogger.debug("span attribute dropped: limit exceeded");
            return this;
        }
        attributes.put(key, value);
        return this;
    }

    @Override
    public Span addEvent(String name) {
        if (events.size() >= spanLimits.getMaxEvents()) {
            spanLimits.recordDroppedEvent();
            SpanLogger.debug("span event dropped: limit exceeded");
            return this;
        }
        events.add(new SpanEvent(name, anchoredClock.now(), Collections.emptyMap()));
        return this;
    }

    @Override
    public Span addEvent(String name, Map<AttributeKey<?>, Object> attributes) {
        if (events.size() >= spanLimits.getMaxEvents()) {
            spanLimits.recordDroppedEvent();
            SpanLogger.debug("span event dropped: limit exceeded");
            return this;
        }
        events.add(new SpanEvent(name, anchoredClock.now(), attributes));
        return this;
    }

    AnchoredClock getAnchoredClock() {
        return anchoredClock;
    }

    @Override
    public Span recordException(Throwable exception) {
        this.recordedException = exception;
        SpanLogger.debug("span exception recorded: " + exception.getClass().getName());
        return this;
    }

    @Override
    public Span setStatus(SpanStatus status, String description) {
        this.status = status == null ? SpanStatus.UNSET : status;
        this.statusDescription = description;
        SpanLogger.debug("span status set: " + this.status);
        return this;
    }

    public Map<AttributeKey<?>, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public List<SpanEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<SpanLink> getLinks() {
        return Collections.unmodifiableList(links);
    }

    public SpanStatus getStatus() {
        return status;
    }

    public String getStatusDescription() {
        return statusDescription;
    }

    public Throwable getRecordedException() {
        return recordedException;
    }
}
