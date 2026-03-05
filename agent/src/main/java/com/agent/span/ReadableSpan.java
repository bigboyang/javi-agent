package com.agent.span;

import java.util.List;
import java.util.Map;

/**
 * Span의 읽기 전용 데이터 접근 인터페이스.
 * Exporter가 Span 데이터를 직렬화할 때 사용한다.
 */
public interface ReadableSpan {
    String getName();
    SpanContext getContext();
    SpanKind getKind();
    long getStartTimeNanos();
    long getEndTimeNanos();
    boolean isRecording();
    SpanStatus getStatus();
    String getStatusDescription();
    Map<AttributeKey<?>, Object> getAttributes();
    List<SpanEvent> getEvents();
    List<SpanLink> getLinks();
    int getDroppedAttributeCount();
    int getDroppedEventCount();
}
