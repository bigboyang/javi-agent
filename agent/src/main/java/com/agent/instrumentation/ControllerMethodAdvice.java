package com.agent.instrumentation;

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

        String spanName = resolveSpanName(typeName, methodName);
        Tracer tracer = AgentRuntime.tracer();
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        // HTTP 속성 추가 (OTel HTTP 시맨틱 컨벤션)
        addHttpAttributes(span);

        Scope scope = span.makeCurrent();
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
        }
        state.scope.close();
        state.span.end();
    }

    /**
     * Spring RequestContextHolder를 reflection으로 접근해 "METHOD /uri" 형식으로 반환.
     * Spring이 없거나 request context가 없으면 "SimpleClassName#methodName" 반환.
     */
    private static String resolveSpanName(String typeName, String methodName) {
        try {
            Class<?> rch = Class.forName(
                    "org.springframework.web.context.request.RequestContextHolder");
            Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
            if (attrs != null) {
                Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
                String httpMethod = (String) req.getClass().getMethod("getMethod").invoke(req);
                String uri = (String) req.getClass().getMethod("getRequestURI").invoke(req);
                return httpMethod + " " + uri;
            }
        } catch (Throwable ignored) {
            // Spring 없음 or request context 없음 → fallback
        }
        int dot = typeName.lastIndexOf('.');
        String simpleName = dot >= 0 ? typeName.substring(dot + 1) : typeName;
        return simpleName + "#" + methodName;
    }

    /**
     * 현재 HTTP 요청 정보를 스팬 속성으로 추가 (OTel semantic conventions).
     */
    private static void addHttpAttributes(Span span) {
        try {
            Class<?> rch = Class.forName(
                    "org.springframework.web.context.request.RequestContextHolder");
            Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
            if (attrs != null) {
                Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
                String method = (String) req.getClass().getMethod("getMethod").invoke(req);
                String uri = (String) req.getClass().getMethod("getRequestURI").invoke(req);
                span.setAttribute("http.method", method);
                span.setAttribute("http.target", uri);
                Object queryString = req.getClass().getMethod("getQueryString").invoke(req);
                if (queryString != null) {
                    span.setAttribute("http.url", uri + "?" + queryString);
                }
            }
        } catch (Throwable ignored) {}
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
