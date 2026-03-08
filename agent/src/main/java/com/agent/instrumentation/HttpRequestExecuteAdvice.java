package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Context;
import com.agent.span.SpanContext;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for outgoing HTTP request propagation.
 *
 * 대상: org.springframework.http.client.ClientHttpRequest 구현체의 execute() 메서드
 * 현재 ThreadLocal span context를 읽어 W3C traceparent 헤더를 inject한다.
 * 이를 통해 downstream 서비스로 분산 트레이싱 컨텍스트가 전파된다.
 */
public final class HttpRequestExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object request) {
        SpanContext ctx = Context.currentSpan().getContext();
        if (!ctx.isValid()) return;

        try {
            Object headers = request.getClass().getMethod("getHeaders").invoke(request);
            String flagsHex = ctx.getTraceFlags().isSampled() ? "01" : "00";
            String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + flagsHex;
            // HttpHeaders.set(String, String) — MultiValueMap 구현체
            headers.getClass().getMethod("set", String.class, String.class)
                    .invoke(headers, "traceparent", traceparent);
            AgentLogger.debug("[HTTP-PROPAGATE] inject traceparent=" + traceparent);
        } catch (Throwable ignored) {}
    }

    private HttpRequestExecuteAdvice() {}
}
