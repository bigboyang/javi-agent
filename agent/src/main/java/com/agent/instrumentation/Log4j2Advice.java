package com.agent.instrumentation;

import com.agent.logs.SdkLogEmitter;
import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;

/**
 * Log4j2 로그 이벤트 발생 시 ThreadContext 주입 및 로그 메시지 수집.
 */
public class Log4j2Advice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object event) {
        try {
            if (event instanceof LogEvent) {
                LogEvent logEvent = (LogEvent) event;

                // 1. ThreadContext 주입
                Span currentSpan = Context.currentSpan();
                SpanContext ctx = currentSpan.getContext();
                if (ctx != null && ctx.isValid()) {
                    ThreadContext.put("traceId", ctx.getTraceId());
                    ThreadContext.put("spanId", ctx.getSpanId());
                }

                // 2. 로그 메시지 수집
                SdkLogEmitter.emit(
                        logEvent.getLoggerName(),
                        logEvent.getLevel().toString(),
                        logEvent.getMessage().getFormattedMessage(),
                        Collections.emptyMap()
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
