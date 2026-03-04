package com.agent.span;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Span 내부 동작을 디버깅하기 위한 로거.
 */
final class SpanLogger {
    private static final Logger LOGGER = Logger.getLogger(SpanLogger.class.getName());

    private SpanLogger() {}

    static void debug(String message) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, message);
        }
    }
}
