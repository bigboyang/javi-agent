package com.agent.instrumentation;

import com.agent.common.utils.UrlSanitizer;
import com.agent.propagation.ContextPropagators;
import com.agent.propagation.TextMapSetter;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import java.net.http.HttpRequest;

/**
 * Java 11+ HttpClient 계측.
 * HttpClientImpl.send() 메서드를 추적합니다.
 *
 * HttpRequest는 불변(immutable)이므로 전파 헤더 주입 시
 * HttpRequest.Builder를 carrier로 사용해 재빌드한 뒤 파라미터 슬롯에 write-back합니다.
 * (@Advice.Argument readOnly=false → ByteBuddy가 로컬 변수 슬롯에 새 값을 ASTORE)
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

        // HttpRequest는 불변이므로 Builder를 carrier로 사용하여 헤더를 주입한 뒤 재빌드한다.
        // TextMapSetter<HttpRequest.Builder>: builder.header()가 중복 헤더를 허용하므로
        // 기존 요청의 헤더를 먼저 복사한 후 전파 헤더를 추가한다.
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(request.uri())
                        .method(request.method(),
                                request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
                request.timeout().ifPresent(rb::timeout);
                // HttpRequest.Builder.header()는 추가만 하므로 전파 필드는 미리 제외하고 복사한다.
                // 이후 inject가 새 값을 유일하게 추가하도록 보장.
                java.util.Collection<String> propagationFields =
                        ContextPropagators.getTextMapPropagator().fields();
                request.headers().map().forEach((name, values) -> {
                    if (!propagationFields.contains(name.toLowerCase())
                            && !"baggage".equalsIgnoreCase(name)) {
                        values.forEach(value -> rb.header(name, value));
                    }
                });

                TextMapSetter<HttpRequest.Builder> setter = (builder, key, value) -> builder.header(key, value);

                // 1. traceparent + tracestate 주입 (TraceContextPropagator가 ctx.getTraceFlags().asByte() 사용)
                ContextPropagators.getTextMapPropagator().inject(ctx, rb, setter);

                // 2. W3C Baggage 주입
                ContextPropagators.getBaggagePropagator()
                        .inject(com.agent.span.Context.currentBaggage(), rb, setter);

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
