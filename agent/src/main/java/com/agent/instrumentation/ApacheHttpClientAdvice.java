package com.agent.instrumentation;

import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.apache.http.HttpRequest;
import org.apache.http.HttpHost;

/**
 * Apache HttpClient 계측 (v4/v5).
 * doExecute() 시 스팬을 생성하고 traceparent를 헤더에 주입합니다.
 */
public final class ApacheHttpClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) HttpHost target,
            @Advice.Argument(1) HttpRequest request) {
        
        if (request == null) return null;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.http.apache");
        String method = "HTTP";
        try {
            method = request.getRequestLine().getMethod();
        } catch (Throwable ignored) {}

        String host = target != null ? target.toURI() : "unknown";
        String uri = "unknown";
        try {
            uri = request.getRequestLine().getUri();
        } catch (Throwable ignored) {}

        String spanName = method + " " + host + uri;

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("http.method", method);
        span.setAttribute("url.full", host + uri);

        // 컨텍스트 전파 (Injection)
        try {
            SpanContext ctx = span.getContext();
            String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-01";
            request.addHeader("traceparent", traceparent);
        } catch (Throwable ignored) {}

        return new State(span, span.makeCurrent());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(com.agent.span.SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();
    }

    public static final class State {
        public final Span span;
        public final com.agent.span.Scope scope;
        public State(Span span, com.agent.span.Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }
}
