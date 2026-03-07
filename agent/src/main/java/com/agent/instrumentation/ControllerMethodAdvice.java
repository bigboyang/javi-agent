package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for controller methods.
 *
 * 스팬 이름: OTel HTTP 시맨틱 컨벤션에 따라 "METHOD /route" 형식 사용.
 * Spring RequestContextHolder를 reflection으로 접근해 실제 HTTP 정보를 가져온다.
 * Spring이 없는 환경에서는 "SimpleClassName#methodName" 으로 fallback.
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
        try {
            Class<?> rch = Class.forName(
                    "org.springframework.web.context.request.RequestContextHolder");
            Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
            if (attrs != null) {
                Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
                httpMethod = (String) req.getClass().getMethod("getMethod").invoke(req);
                uri = (String) req.getClass().getMethod("getRequestURI").invoke(req);
                spanName = httpMethod + " " + uri;
            } else {
                int dot = typeName.lastIndexOf('.');
                spanName = (dot >= 0 ? typeName.substring(dot + 1) : typeName) + "#" + methodName;
            }
        } catch (Throwable ignored) {
            int dot = typeName.lastIndexOf('.');
            spanName = (dot >= 0 ? typeName.substring(dot + 1) : typeName) + "#" + methodName;
        }

        Tracer tracer = AgentRuntime.tracer();
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        if (httpMethod != null) {
            span.setAttribute("http.method", httpMethod);
        }
        if (uri != null) {
            span.setAttribute("http.target", uri);
        }

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[HTTP] span started: " + spanName);
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

    static final class State {
        private final Span span;
        private final Scope scope;

        State(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }

    private ControllerMethodAdvice() {}
}
