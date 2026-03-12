package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Context;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * Spring @Async 계측.
 *
 * 대상: org.springframework.aop.interceptor.AsyncExecutionInterceptor.invoke()
 *
 * 흐름:
 * 1. invoke() 진입 시 현재 span을 부모로 하는 INTERNAL span 생성 (submit span)
 * 2. invoke() 종료 시 submit span 즉시 종료
 * 3. 내부 executor.submit()은 ExecutorServiceAdvice가 처리 → 워커 스레드에서 컨텍스트 복원
 *
 * Spring은 app classloader에 있으므로 bootstrap injection 없이 일반 AgentBuilder 사용.
 */
public final class SpringAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) Object invocation) {
        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;

        String spanName = "async";
        try {
            Method getMethod = invocation.getClass().getMethod("getMethod");
            Method method = (Method) getMethod.invoke(invocation);
            spanName = "async " + method.getDeclaringClass().getSimpleName()
                    + "#" + method.getName();
        } catch (Throwable ignored) {}

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.spring.async");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute("async.framework", "spring");

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        AgentLogger.debug("[Async] submit span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();

        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    public static final class State {
        public final Span span;
        public final Scope scope;
        public final String prevTraceId;
        public final String prevSpanId;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId) {
            this.span = span;
            this.scope = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId = prevSpanId;
        }
    }

    private SpringAsyncAdvice() {}
}
