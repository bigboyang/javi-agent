package com.agent.span;

import com.agent.common.utils.time.AnchoredClock;
import com.agent.common.utils.time.Clock;
import com.agent.trace.InstrumentationScopeInfo;
import com.agent.trace.processor.SpanProcessor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SDK 기본 스팬 구현체.
 *
 * 동시성 안전:
 *  - end() 이중 호출 방지: AtomicBoolean hasEnded
 *  - attributes: ConcurrentHashMap + AtomicInteger CAS (Datadog 방식 — 글로벌 락 없음)
 *    limit은 best-effort: 동시 삽입 시 ±1 허용 (성능 우선)
 *  - events/name/status: synchronized(lock) + 락 내부 isRecording() 재확인 (TOCTOU 방지)
 *  - status/statusDescription: volatile (쓰기는 락 내부에서만, 읽기는 락 불필요)
 *  - droppedAttributeCount/droppedEventCount: AtomicInteger (락 불필요)
 *  - endTimeNanos: hasEnded CAS 이후에만 쓰므로 volatile로 충분
 */
final class SdkSpan implements Span, ReadableSpan {
    private volatile String name;
    private final SpanContext context;
    private final InstrumentationScopeInfo instrumentationScopeInfo;
    private final long startTimeNanos;
    private final List<SpanLink> links;
    private final SpanLimits spanLimits;
    private final SpanKind spanKind;
    private final SpanProcessor spanProcessor;
    private final AnchoredClock anchoredClock;
    private final Clock clock;
    private final long startNanoTime;

    // events/name/status 복합 연산 보호 — attributes에는 사용 안 함 (Datadog 방식)
    private final Object lock = new Object();
    private final AtomicBoolean hasEnded = new AtomicBoolean(false);

    // Datadog 방식: ConcurrentHashMap + AtomicInteger CAS로 글로벌 락 제거
    // ConcurrentHashMap은 버킷 단위 분할 잠금으로 다른 키에 대한 동시 쓰기를 허용
    private final ConcurrentHashMap<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicInteger attributeCount = new AtomicInteger(0);

    // events — ArrayList는 thread-safe하지 않으므로 synchronized(lock) 유지
    private final List<SpanEvent> events = new ArrayList<>();

    // volatile: 읽기는 락 없이, 쓰기는 락 내부에서만 수행
    private volatile SpanStatus status = SpanStatus.UNSET;
    private volatile String statusDescription;

    // AtomicInteger: 락 없이 안전하게 증가/읽기 가능
    private final AtomicInteger droppedAttributeCount = new AtomicInteger(0);
    private final AtomicInteger droppedEventCount = new AtomicInteger(0);

    private volatile long endTimeNanos;
    private long endNanoTime;

    SdkSpan(
            String name,
            SpanContext context,
            InstrumentationScopeInfo instrumentationScopeInfo,
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
        this.instrumentationScopeInfo = instrumentationScopeInfo;
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
        if (newName == null) return this;
        synchronized (lock) {
            if (!isRecording()) return this;
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

    /**
     * Datadog 방식: ConcurrentHashMap + AtomicInteger CAS로 글로벌 락 없이 attribute 추가.
     *
     * 기존 키 업데이트는 count 변화 없으므로 limit 체크 생략.
     * 신규 키는 CAS 루프로 슬롯을 선점한 뒤 putIfAbsent로 삽입.
     * limit은 best-effort: 극히 드문 동시 삽입 경합 시 ±1 허용.
     */
    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
        if (key == null || !isRecording()) return this;

        // 기존 키 업데이트: count 변화 없으므로 limit 체크 불필요, 락 없이 ConcurrentHashMap에 바로 덮어쓰기
        if (attributes.containsKey(key)) {
            attributes.put(key, value);
            return this;
        }

        // 신규 키: CAS 루프로 슬롯 선점
        int current;
        do {
            current = attributeCount.get();
            if (current >= spanLimits.getMaxAttributes()) {
                droppedAttributeCount.incrementAndGet();
                SpanLogger.debug("span attribute dropped: limit exceeded");
                return this;
            }
        } while (!attributeCount.compareAndSet(current, current + 1));

        // 슬롯 선점 성공 — putIfAbsent로 다른 스레드의 동시 삽입 방어
        if (attributes.putIfAbsent(key, value) != null) {
            // 다른 스레드가 같은 키를 먼저 삽입 → 카운트 롤백 후 값만 업데이트
            attributeCount.decrementAndGet();
            attributes.put(key, value);
        }

        return this;
    }

    @Override
    public Span addEvent(String name) {
        return addEvent(name, Collections.emptyMap());
    }

    @Override
    public Span addEvent(String name, Map<AttributeKey<?>, Object> eventAttributes) {
        // events는 ArrayList(비thread-safe) + 크기 체크+추가 복합 연산 → synchronized 유지
        synchronized (lock) {
            if (!isRecording()) return this;
            if (events.size() >= spanLimits.getMaxEvents()) {
                droppedEventCount.incrementAndGet();
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
        if (exception == null) return this;
        // isRecording() 체크는 addEvent 내부의 synchronized 블록에서 수행
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
        // status + statusDescription 동시 변경 → 원자성 보장을 위해 synchronized 유지
        synchronized (lock) {
            if (!isRecording()) return this;
            this.status = status == null ? SpanStatus.UNSET : status;
            this.statusDescription = description;
            SpanLogger.debug("span status set: " + this.status);
        }
        return this;
    }

    // ---- ReadableSpan ----

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return instrumentationScopeInfo;
    }

    @Override
    public Map<AttributeKey<?>, Object> getAttributes() {
        // ConcurrentHashMap은 항상 thread-safe — hasEnded 분기 불필요, 락 없이 직접 반환
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public List<SpanEvent> getEvents() {
        // span이 종료된 후에는 더 이상 write가 발생하지 않으므로 복사 불필요
        if (hasEnded.get()) {
            return Collections.unmodifiableList(events);
        }
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
        return status; // volatile 읽기 — 락 불필요
    }

    @Override
    public String getStatusDescription() {
        return statusDescription; // volatile 읽기 — 락 불필요
    }

    @Override
    public int getDroppedAttributeCount() {
        return droppedAttributeCount.get(); // AtomicInteger — 락 불필요
    }

    @Override
    public int getDroppedEventCount() {
        return droppedEventCount.get(); // AtomicInteger — 락 불필요
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
