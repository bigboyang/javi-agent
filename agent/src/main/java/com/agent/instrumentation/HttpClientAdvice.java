package com.agent.instrumentation;

import com.agent.common.utils.UrlSanitizer;
import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.net.URI;

/**
 * ByteBuddy advice for HTTP client calls via Spring RestTemplate.
 *
 * 대상: RestTemplate.doExecute(URI, HttpMethod, RequestCallback, ResponseExtractor)
 * 스팬 이름: "METHOD host/path" 형식 (OTel HTTP 클라이언트 시맨틱 컨벤션)
 *
 * HttpMethod를 Object로 받는 이유: Spring 클래스를 agent JAR에 직접 임포트하지 않아
 * 클래스로더 격리 문제를 방지하기 위함이다. toString()으로 메서드 이름을 추출한다.
 */
public final class HttpClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) URI url,
            @Advice.Argument(1) Object httpMethod) {

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.http.client");

        String method = httpMethod != null ? httpMethod.toString() : "HTTP";

        // span name 인라인 계산
        String spanName;
        if (url == null) {
            spanName = method + " unknown";
        } else {
            String path = url.getPath();
            if (path == null || path.isEmpty()) path = "/";
            spanName = method + " " + url.getHost() + path;
        }

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("http.request.method", method);
        if (url != null) {
            span.setAttribute("url.full", UrlSanitizer.sanitize(url.toString()));
            span.setAttribute("server.address", url.getHost());
            if (url.getPort() > 0) {
                span.setAttribute("server.port", (long) url.getPort());
            }
            span.setAttribute("url.path", url.getPath() != null ? url.getPath() : "/");
        }

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[HTTP-CLIENT] span started: " + spanName);
        return new State(span, scope);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[HTTP-CLIENT] span error: " + error.getMessage());
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

    private HttpClientAdvice() {}
}
