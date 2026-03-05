package com.agent.span;

import com.agent.common.utils.time.AnchoredClock;
import com.agent.common.utils.time.Clock;
import com.agent.trace.processor.SpanProcessor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SDK 기본 스팬 구현체.
 *
 * 동시성 안전:
 *  - end() 이중 호출 방지: AtomicBoolean hasEnded
 *  - attributes/events/status 변경: synchronized(lock)
 *  - endTimeNanos: hasEnded CAS 이후에만 쓰므로 volatile로 충분
 */
final class SdkSpan implements Span, ReadableSpan {
    private volatile String name;
    private final SpanContext context;
    private final long startTimeNanos;
    private final List<SpanLink> links;
    private final SpanLimits spanLimits;
    private final SpanKind spanKind;
    private final SpanProcessor spanProcessor;
    private final AnchoredClock anchoredClock;
    private final Clock clock;
    private final long startNanoTime;

    private final Object lock = new Object();
    private final AtomicBoolean hasEnded = new AtomicBoolean(false);

    // mutable state — all accessed under lock
    private final Map<AttributeKey<?>, Object> attributes = new LinkedHashMap<>();
    private final List<SpanEvent> events = new ArrayList<>();
    private SpanStatus status = SpanStatus.UNSET;
    private String statusDescription;
    private int droppedAttributeCount;
    private int droppedEventCount;

    private volatile long endTimeNanos;
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
        if (!hasEnded.compareAndSet(false, true)) {
            return; // 이미 종료됨 — 이중 호출 무시
        }
        endTimeNanos = anchoredClock.now();
        endNanoTime = clock.nanoTime();
        if (spanProcessor != null) {
            spanProcessor.onEnd(this);
        }
    }

    @Override
    public boolean isRecording() {
        return !hasEnded.get();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Span updateName(String newName) {
        if (newName != null && isRecording()) {
            this.name = newName;
        }
        return this;
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
        return endNanoTime == 0 ? 0 : endNanoTime - startNanoTime;
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

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
        if (key == null || !isRecording()) {
            return this;
        }
        synchronized (lock) {
            if (attributes.containsKey(key)) {
                // 덮어쓰기: 크기 변화 없음
                attributes.put(key, value);
            } else if (attributes.size() < spanLimits.getMaxAttributes()) {
                attributes.put(key, value);
            } else {
                droppedAttributeCount++;
                SpanLogger.debug("span attribute dropped: limit exceeded");
            }
        }
        return this;
    }

    @Override
    public Span addEvent(String name) {
        return addEvent(name, Collections.emptyMap());
    }

    @Override
    public Span addEvent(String name, Map<AttributeKey<?>, Object> eventAttributes) {
        if (!isRecording()) return this;
        synchronized (lock) {
            if (events.size() >= spanLimits.getMaxEvents()) {
                droppedEventCount++;
                SpanLogger.debug("span event dropped: limit exceeded");
                return this;
            }
            events.add(new SpanEvent(name, anchoredClock.now(),
                    eventAttributes == null ? Collections.emptyMap() : eventAttributes));
        }
        return this;
    }

    @Override
    public Span recordException(Throwable exception) {
        if (exception == null || !isRecording()) return this;
        Map<AttributeKey<?>, Object> attrs = new LinkedHashMap<>();
        attrs.put(AttributeKey.stringKey("exception.type"), exception.getClass().getName());
        if (exception.getMessage() != null) {
            attrs.put(AttributeKey.stringKey("exception.message"), exception.getMessage());
        }
        attrs.put(AttributeKey.stringKey("exception.stacktrace"), stackTraceToString(exception));
        return addEvent("exception", attrs);
    }

    @Override
    public Span setStatus(SpanStatus status, String description) {
        if (!isRecording()) return this;
        synchronized (lock) {
            this.status = status == null ? SpanStatus.UNSET : status;
            this.statusDescription = description;
        }
        SpanLogger.debug("span status set: " + this.status);
        return this;
    }

    // ---- ReadableSpan ----

    @Override
    public Map<AttributeKey<?>, Object> getAttributes() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    @Override
    public List<SpanEvent> getEvents() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    @Override
    public List<SpanLink> getLinks() {
        return Collections.unmodifiableList(links);
    }

    @Override
    public SpanStatus getStatus() {
        synchronized (lock) {
            return status;
        }
    }

    @Override
    public String getStatusDescription() {
        synchronized (lock) {
            return statusDescription;
        }
    }

    @Override
    public int getDroppedAttributeCount() {
        synchronized (lock) {
            return droppedAttributeCount;
        }
    }

    @Override
    public int getDroppedEventCount() {
        synchronized (lock) {
            return droppedEventCount;
        }
    }

    AnchoredClock getAnchoredClock() {
        return anchoredClock;
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
