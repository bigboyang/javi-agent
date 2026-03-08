package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.propagation.MapTextMapGetter;
import com.agent.propagation.TraceContextPropagator;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanBuilder;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for controller methods.
 *
 * 스팬 이름: OTel HTTP 시맨틱 컨벤션에 따라 "METHOD /route" 형식 사용.
 * Spring RequestContextHolder를 reflection으로 접근해 실제 HTTP 정보를 가져온다.
 * Spring이 없는 환경에서는 "SimpleClassName#methodName" 으로 fallback.
 * 들어오는 W3C traceparent 헤더를 추출해 분산 트레이싱 컨텍스트를 이어받는다.
 */
public final class ControllerMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName) {

        // span name: Spring RequestContextHolder로 HTTP 정보 추출, fallback은 ClassName#method
        String spanName;
        String httpMethod = null;
        String uri = null;
        SpanContext remoteParent = null;
        try {
            Class<?> rch = Class.forName(
                    "org.springframework.web.context.request.RequestContextHolder");
            Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
            if (attrs != null) {
                Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
                httpMethod = (String) req.getClass().getMethod("getMethod").invoke(req);
                uri = (String) req.getClass().getMethod("getRequestURI").invoke(req);
                spanName = httpMethod + " " + uri;

                // W3C traceparent 헤더 추출 — 분산 트레이싱 컨텍스트 전파
                try {
                    String traceparent = (String) req.getClass()
                            .getMethod("getHeader", String.class).invoke(req, "traceparent");
                    if (traceparent != null) {
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("traceparent", traceparent);
                        String tracestate = (String) req.getClass()
                                .getMethod("getHeader", String.class).invoke(req, "tracestate");
                        if (tracestate != null) headers.put("tracestate", tracestate);
                        SpanContext extracted = TraceContextPropagator.extractStatic(
                                headers, new MapTextMapGetter());
                        if (extracted.isValid()) remoteParent = extracted;
                    }
                } catch (Throwable ignored) {}
            } else {
                int dot = typeName.lastIndexOf('.');
                spanName = (dot >= 0 ? typeName.substring(dot + 1) : typeName) + "#" + methodName;
            }
        } catch (Throwable ignored) {
            int dot = typeName.lastIndexOf('.');
            spanName = (dot >= 0 ? typeName.substring(dot + 1) : typeName) + "#" + methodName;
        }

        Tracer tracer = AgentRuntime.tracer();
        SpanBuilder builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.SERVER);
        if (remoteParent != null) {
            builder.setParent(remoteParent);
        }
        Span span = builder.startSpan();

        if (httpMethod != null) {
            span.setAttribute("http.method", httpMethod);
        }
        if (uri != null) {
            span.setAttribute("http.target", uri);
        }

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[HTTP] span started: " + spanName
                + (remoteParent != null ? " (propagated traceId=" + remoteParent.getTraceId() + ")" : ""));
        return new State(span, scope);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) {
            return;
        }
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(com.agent.span.SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[HTTP] span error: " + error.getMessage());
        }
        state.scope.close();
        state.span.end();
    }

    public static final class State {
        public final Span span;
        public final Scope scope;

        public State(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }

    private ControllerMethodAdvice() {}
}
