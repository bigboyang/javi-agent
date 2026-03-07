package com.agent.span;

import com.agent.logs.AgentLogger;

/**
 * Span 내부 동작을 디버깅하기 위한 로거.
 */
final class SpanLogger {

    private SpanLogger() {}

    static void debug(String message) {
        AgentLogger.debug(message);
    }
}
