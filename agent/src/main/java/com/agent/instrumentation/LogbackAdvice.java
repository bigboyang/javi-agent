package com.agent.instrumentation;

import com.agent.logs.SdkLogEmitter;
import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

/**
 * Logback 로그 이벤트 발생 시 MDC 주입 및 로그 메시지 수집.
 */
public class LogbackAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object event) {
        try {
            // ILoggingEvent 인스턴스인지 확인 (클래스 로더 격리 대비)
            if (event instanceof ch.qos.logback.classic.spi.ILoggingEvent) {
                ch.qos.logback.classic.spi.ILoggingEvent loggingEvent = (ch.qos.logback.classic.spi.ILoggingEvent) event;

                // 1. MDC 주입 (기존 기능)
                Span currentSpan = Context.currentSpan();
                SpanContext ctx = currentSpan.getContext();
                if (ctx != null && ctx.isValid()) {
                    MDC.put("traceId", ctx.getTraceId());
                    MDC.put("spanId", ctx.getSpanId());
                }

                // 2. 로그 메시지 수집 (신규 기능)
                SdkLogEmitter.emit(
                        loggingEvent.getLoggerName(),
                        loggingEvent.getLevel().toString(),
                        loggingEvent.getFormattedMessage(),
                        Collections.emptyMap()
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
