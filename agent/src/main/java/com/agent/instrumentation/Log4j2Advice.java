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
 *
 * <p>MDC 주입 전략 (LogbackAdvice와 동일):
 * <ul>
 *   <li>1순위: ControllerMethodAdvice가 span 시작 시 MDC를 설정 — 이 경우 이미 traceId 존재
 *   <li>2순위 (fallback): traceId가 없는 경우 log() 진입 시 일시 주입, 종료 후 복원
 * </ul>
 */
public class Log4j2Advice {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Argument(0) Object event) {
        try {
            if (event instanceof LogEvent) {
                LogEvent logEvent = (LogEvent) event;

                // 로그 메시지 수집
                SdkLogEmitter.emit(
                        logEvent.getLoggerName(),
                        logEvent.getLevel().toString(),
                        logEvent.getMessage().getFormattedMessage(),
                        Collections.emptyMap()
                );

                // ThreadContext fallback: logInjection이 활성화된 경우에만 주입
                if (com.agent.config.RemoteConfigHolder.get().isLogInjection()
                        && ThreadContext.get("traceId") == null) {
                    Span currentSpan = Context.currentSpan();
                    SpanContext ctx = currentSpan.getContext();
                    if (ctx != null && ctx.isValid()) {
                        ThreadContext.put("traceId", ctx.getTraceId());
                        ThreadContext.put("spanId", ctx.getSpanId());
                        return true; // fallback으로 설정함 → exit에서 제거 필요
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean mdcInjected) {
        if (mdcInjected) {
            try {
                ThreadContext.remove("traceId");
                ThreadContext.remove("spanId");
            } catch (Throwable ignored) {
            }
        }
    }
}
