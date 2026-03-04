package com.agent.span;

/**
 * Span의 역할을 나타내는 종류.
 */
public enum SpanKind {
    INTERNAL,
    SERVER,
    CLIENT,
    PRODUCER,
    CONSUMER
}
