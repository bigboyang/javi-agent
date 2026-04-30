package com.agent.instrumentation;

import com.agent.common.utils.UrlSanitizer;
import com.agent.span.Span;
import com.agent.span.SpanContext;
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
    public static State onEnter(@Advice.Argument(value = 0, readOnly = false) HttpRequest request) {
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
        if (host != null) span.setAttribute("peer.service", host);

        // Inject W3C traceparent by rebuilding the immutable HttpRequest (Java 11 compatible)
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            try {
                String sampledFlag = span.isRecording() ? "01" : "00";
                String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + sampledFlag;
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(request.uri())
                        .method(request.method(),
                                request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
                request.timeout().ifPresent(rb::timeout);
                request.headers().map().forEach((name, values) ->
                        values.forEach(value -> rb.header(name, value)));
                rb.header("traceparent", traceparent);
                request = rb.build();
            } catch (Throwable ignored) {}
        }

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
