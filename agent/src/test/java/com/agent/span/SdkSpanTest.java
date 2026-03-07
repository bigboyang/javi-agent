package com.agent.span;

import com.agent.common.utils.time.AnchoredClock;
import com.agent.common.utils.time.Clock;
import com.agent.trace.InstrumentationScopeInfo;
import com.agent.trace.processor.NoopSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SdkSpanTest {

    private SdkSpan span;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.system();
        AnchoredClock anchoredClock = AnchoredClock.create(clock);
        SpanContext ctx = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                SpanId.getInvalid(),
                true);
        InstrumentationScopeInfo scopeInfo = new InstrumentationScopeInfo("test", null, null);
        span = new SdkSpan(
                "test-span",
                ctx,
                scopeInfo,
                anchoredClock.now(),
                Collections.emptyList(),
                new SpanLimits(3, 3, 3),
                SpanKind.INTERNAL,
                NoopSpanProcessor.getInstance(),
                anchoredClock,
                clock,
                clock.nanoTime());
    }

    // ─── isRecording / end() ────────────────────────────────────────────────

    @Test
    void isRecordingTrueBeforeEnd() {
        assertTrue(span.isRecording());
    }

    @Test
    void isRecordingFalseAfterEnd() {
        span.end();
        assertFalse(span.isRecording());
    }

    @Test
    void endIsIdempotent() {
        span.end();
        long first = span.getEndTimeNanos();
        span.end(); // 두 번째 호출은 무시되어야 함
        assertEquals(first, span.getEndTimeNanos(), "end() 이중 호출 시 endTimeNanos 변경 금지");
    }

    @Test
    void endTimeNanosSetAfterEnd() {
        assertEquals(0, span.getEndTimeNanos());
        span.end();
        assertTrue(span.getEndTimeNanos() > 0);
    }

    // ─── updateName ─────────────────────────────────────────────────────────

    @Test
    void updateNameWhileRecording() {
        span.updateName("new-name");
        assertEquals("new-name", span.getName());
    }

    @Test
    void updateNameAfterEndIgnored() {
        span.end();
        span.updateName("should-be-ignored");
        assertEquals("test-span", span.getName());
    }

    // ─── setAttribute ────────────────────────────────────────────────────────

    @Test
    void setAttributeOverwriteDoesNotGrowMap() {
        span.setAttribute("key", "v1");
        span.setAttribute("key", "v2");
        Map<AttributeKey<?>, Object> attrs = span.getAttributes();
        assertEquals(1, attrs.size(), "덮어쓰기는 크기를 늘리면 안 됨");
        assertEquals("v2", attrs.get(AttributeKey.stringKey("key")));
    }

    @Test
    void setAttributeDropsWhenLimitReached() {
        span.setAttribute("a", "1");
        span.setAttribute("b", "2");
        span.setAttribute("c", "3");
        span.setAttribute("d", "4"); // 4번째 → limit(3) 초과

        assertEquals(3, span.getAttributes().size());
        assertEquals(1, span.getDroppedAttributeCount());
    }

    @Test
    void setAttributeIgnoredAfterEnd() {
        span.end();
        span.setAttribute("key", "value");
        assertTrue(span.getAttributes().isEmpty());
    }

    // ─── addEvent ────────────────────────────────────────────────────────────

    @Test
    void addEventBasic() {
        span.addEvent("click");
        List<SpanEvent> events = span.getEvents();
        assertEquals(1, events.size());
        assertEquals("click", events.get(0).getName());
    }

    @Test
    void addEventDropsWhenLimitReached() {
        span.addEvent("e1");
        span.addEvent("e2");
        span.addEvent("e3");
        span.addEvent("e4"); // 초과

        assertEquals(3, span.getEvents().size());
        assertEquals(1, span.getDroppedEventCount());
    }

    // ─── recordException ─────────────────────────────────────────────────────

    @Test
    void recordExceptionCreatesOtelCompliantEvent() {
        RuntimeException ex = new RuntimeException("boom");
        span.recordException(ex);

        List<SpanEvent> events = span.getEvents();
        assertEquals(1, events.size());

        SpanEvent event = events.get(0);
        assertEquals("exception", event.getName());

        Map<AttributeKey<?>, Object> attrs = event.getAttributes();
        assertEquals(RuntimeException.class.getName(),
                attrs.get(AttributeKey.stringKey("exception.type")));
        assertEquals("boom",
                attrs.get(AttributeKey.stringKey("exception.message")));
        assertNotNull(attrs.get(AttributeKey.stringKey("exception.stacktrace")));
    }

    @Test
    void recordExceptionIgnoredAfterEnd() {
        span.end();
        span.recordException(new RuntimeException("late"));
        assertTrue(span.getEvents().isEmpty());
    }

    // ─── setStatus ───────────────────────────────────────────────────────────

    @Test
    void setStatusOk() {
        span.setStatus(SpanStatus.OK, "all good");
        assertEquals(SpanStatus.OK, span.getStatus());
        assertEquals("all good", span.getStatusDescription());
    }

    @Test
    void setStatusNullDefaultsToUnset() {
        span.setStatus(null, null);
        assertEquals(SpanStatus.UNSET, span.getStatus());
    }

    // ─── defensive copies ────────────────────────────────────────────────────

    @Test
    void getAttributesReturnsDefensiveCopy() {
        span.setAttribute("x", "1");
        Map<AttributeKey<?>, Object> copy = span.getAttributes();
        assertThrows(UnsupportedOperationException.class,
                () -> copy.put(AttributeKey.stringKey("y"), "2"));
    }

    @Test
    void getEventsReturnsDefensiveCopy() {
        span.addEvent("e");
        List<SpanEvent> copy = span.getEvents();
        assertThrows(UnsupportedOperationException.class, () -> copy.clear());
    }
}
