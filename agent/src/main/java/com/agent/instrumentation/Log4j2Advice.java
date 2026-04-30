package com.agent.instrumentation;

import com.agent.logs.SdkLogEmitter;
import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.util.HashMap;
import java.util.Map;
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

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) Object event) {
        try {
            if (event instanceof LogEvent) {
                LogEvent logEvent = (LogEvent) event;

                // 로그 메시지 수집
                // Bug #9: getFormattedMessage()가 null을 반환할 수 있으므로 null-safe 처리
                String message = logEvent.getMessage().getFormattedMessage();
                if (message == null) message = logEvent.getMessage().getFormat();
                if (message == null) message = "";
                SdkLogEmitter.emit(
                        logEvent.getLoggerName(),
                        logEvent.getLevel().toString(),
                        message,
                        buildAttributes(logEvent)
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

    private static Map<String, String> buildAttributes(LogEvent event) {
        Map<String, String> attrs = new HashMap<>(6);
        attrs.put("thread.name", event.getThreadName());

        Throwable thrown = event.getThrown();
        if (thrown != null) {
            attrs.put("exception.type", thrown.getClass().getName());
            String msg = thrown.getMessage();
            if (msg != null && !msg.isEmpty()) attrs.put("exception.message", msg);
            java.io.StringWriter sw = new java.io.StringWriter(512);
            thrown.printStackTrace(new java.io.PrintWriter(sw));
            attrs.put("exception.stacktrace", sw.toString());
        }
        return attrs;
    }
}
