package com.agent.instrumentation;

import com.agent.common.utils.UrlSanitizer;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import java.net.http.HttpRequest;

/**
 * Java 11+ HttpClient 계측.
 * HttpClientImpl.send() 메서드를 추적합니다.
 */
public final class JavaHttpClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) HttpRequest request) {
        if (request == null) return null;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.http.java11");
        String method = request.method();
        String host = request.uri().getHost();
        String path = request.uri().getPath();
        String spanName = method + " " + host + (path != null ? path : "/");

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("http.request.method", method);
        span.setAttribute("url.full", UrlSanitizer.sanitize(request.uri().toString()));

        // Java 11 HttpClient은 HttpRequest가 Immutable하므로, 
        // 전파를 위해서는 Builder를 가로채거나 내부의 헤더 필드를 Reflection으로 수정해야 함.
        // 여기서는 기본 스팬 생성만 수행.

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
