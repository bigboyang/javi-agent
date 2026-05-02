package com.agent.instrumentation;

import com.agent.logs.AppLogCollector;
import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

/**
 * Logback 로그 이벤트 발생 시 로그 캡처 및 MDC 주입.
 *
 * <p>로그 캡처(파일 + OTLP)는 {@link AppLogCollector#handleLogEvent}에 위임한다.
 *
 * <p>MDC 주입 전략:
 * <ul>
 *   <li>1순위: ControllerMethodAdvice가 span 시작 시 MDC를 설정 — 이 경우 이미 traceId 존재
 *   <li>2순위 (fallback): traceId가 없는 경우(비-HTTP 스레드 등) callAppenders 진입 시 일시 주입,
 *       callAppenders 종료 후 복원
 * </ul>
 */
public class LogbackAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) Object event) {
        try {
            if (event instanceof ch.qos.logback.classic.spi.ILoggingEvent) {
                ch.qos.logback.classic.spi.ILoggingEvent loggingEvent =
                        (ch.qos.logback.classic.spi.ILoggingEvent) event;

                String message = loggingEvent.getFormattedMessage();
                if (message == null) message = loggingEvent.getMessage();
                if (message == null) message = "";

                // 파일 기록 + OTLP 전송 (AppLogCollector가 단일 진입점)
                AppLogCollector.handleLogEvent(
                        loggingEvent.getLoggerName(),
                        loggingEvent.getLevel().toString(),
                        message,
                        buildAttributes(loggingEvent)
                );

                // MDC fallback: logInjection이 활성화된 경우에만 주입
                if (com.agent.config.RemoteConfigHolder.get().isLogInjection()
                        && MDC.get("traceId") == null) {
                    Span currentSpan = Context.currentSpan();
                    SpanContext ctx = currentSpan.getContext();
                    if (ctx != null && ctx.isValid()) {
                        MDC.put("traceId", ctx.getTraceId());
                        MDC.put("spanId", ctx.getSpanId());
                        return true; // fallback으로 MDC 설정함 → exit에서 제거 필요
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
                MDC.remove("traceId");
                MDC.remove("spanId");
            } catch (Throwable ignored) {
            }
        }
    }

    private static Map<String, String> buildAttributes(ch.qos.logback.classic.spi.ILoggingEvent event) {
        Map<String, String> attrs = new HashMap<>(6);
        attrs.put("thread.name", event.getThreadName());

        ch.qos.logback.classic.spi.IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy != null) {
            attrs.put("exception.type", proxy.getClassName());
            String msg = proxy.getMessage();
            if (msg != null && !msg.isEmpty()) attrs.put("exception.message", msg);
            attrs.put("exception.stacktrace", buildStackTrace(proxy));
        }
        return attrs;
    }

    private static String buildStackTrace(ch.qos.logback.classic.spi.IThrowableProxy proxy) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(proxy.getClassName());
        if (proxy.getMessage() != null) sb.append(": ").append(proxy.getMessage());
        ch.qos.logback.classic.spi.StackTraceElementProxy[] steps = proxy.getStackTraceElementProxyArray();
        if (steps != null) {
            for (ch.qos.logback.classic.spi.StackTraceElementProxy step : steps) {
                sb.append("\n\tat ").append(step.getSTEAsString());
            }
        }
        ch.qos.logback.classic.spi.IThrowableProxy cause = proxy.getCause();
        if (cause != null) {
            sb.append("\nCaused by: ").append(buildStackTrace(cause));
        }
        return sb.toString();
    }
}
