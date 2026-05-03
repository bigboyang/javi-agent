package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.slf4j.MDC;

/**
 * Spring WebClient 비동기 HTTP 클라이언트 계측.
 *
 * 대상: org.springframework.web.reactive.function.client.ExchangeFunctions$DefaultExchangeFunction
 *        .exchange(ClientRequest request)
 *
 * 전략:
 *   1. ClientRequest에서 method/URI 추출 → span 생성
 *   2. ClientRequest를 새로 빌드하여 traceparent 헤더 주입 (@Argument readOnly=false)
 *   3. 반환된 Mono<ClientResponse>를 래핑하여 span 라이프사이클 연결
 *      - doOnSubscribe: scope.close() (Netty IO 스레드 ThreadLocal 정리)
 *      - doOnNext: HTTP 응답 코드 추출
 *      - doOnError: 오류 기록
 *      - doFinally: span.end()
 *
 * WebFluxHandlerAdvice의 Mono 래핑 패턴과 동일한 구조.
 */
public final class WebClientAdvice {

    // hex 룩업 테이블 — String.format("%02x") 대체 (클래스 로드 시 1회 초기화)
    private static final String[] HEX_BYTES;
    static {
        HEX_BYTES = new String[256];
        for (int i = 0; i < 256; i++) {
            HEX_BYTES[i] = String.format("%02x", i);
        }
    }

    // injectTraceparent 캐시: ClientRequest 인터페이스 클래스 및 builder 메서드
    private static volatile Class<?> CLIENT_REQUEST_CLASS = null;
    private static volatile java.lang.reflect.Method FROM_METHOD = null;
    private static volatile java.lang.reflect.Method HEADER_METHOD = null;
    private static volatile java.lang.reflect.Method BUILD_METHOD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request) {

        if (request == null) return null;

        String httpMethod = null;
        String uriPath = null;
        String uriStr = null;

        try {
            Object method = request.getClass().getMethod("method").invoke(request);
            if (method != null) httpMethod = method.toString();
        } catch (Throwable ignored) {}

        try {
            Object uri = request.getClass().getMethod("url").invoke(request);
            if (uri != null) {
                uriStr = uri.toString();
                try { uriPath = (String) uri.getClass().getMethod("getPath").invoke(uri); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        String spanName;
        if (httpMethod != null && uriPath != null) {
            spanName = httpMethod + " " + uriPath;
        } else if (httpMethod != null) {
            spanName = "WebClient " + httpMethod;
        } else {
            spanName = "WebClient request";
        }

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.webclient");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (uriPath != null) span.setAttribute("url.path", uriPath);
        if (uriStr != null) span.setAttribute("url.full", uriStr);
        span.setAttribute("http.framework", "spring-webclient");
        if (uriStr != null) {
            try {
                java.net.URI parsedUri = new java.net.URI(uriStr);
                String host = parsedUri.getHost();
                if (host != null) {
                    span.setAttribute("server.address", host);
                    span.setAttribute("peer.service", host);
                    int port = parsedUri.getPort();
                    if (port > 0) span.setAttribute("server.port", (long) port);
                }
            } catch (Throwable ignored) {}
        }

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        // traceparent 헤더 주입: ClientRequest.from(request).header(...).build()
        if (ctx != null && ctx.isValid()) {
            String traceparent = buildTraceparent(ctx);
            Object mutated = injectTraceparent(request, traceparent);
            if (mutated != null) {
                request = mutated; // @Argument(readOnly=false) write-back
            }
        }

        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Thrown Throwable error,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnMono) {

        if (state == null) return;

        if (error != null || returnMono == null) {
            if (error != null) {
                state.span.recordException(error);
                state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            }
            state.scope.close();
            state.span.end();
            restoreMdc(state);
            return;
        }

        restoreMdc(state);

        Object wrapped = wrapMono(returnMono, state);
        if (wrapped != null) {
            returnMono = wrapped;
        } else {
            state.scope.close();
            state.span.end();
        }
    }

    private static Object wrapMono(Object mono, final State state) {
        try {
            // doOnSubscribe: scope.close() — Netty IO 스레드 ThreadLocal 정리
            java.util.function.Consumer<Object> onSubscribe = s -> {
                try { state.scope.close(); } catch (Throwable ignored) {}
            };
            Object m1 = mono.getClass()
                    .getMethod("doOnSubscribe", java.util.function.Consumer.class)
                    .invoke(mono, onSubscribe);

            // doOnNext: ClientResponse → HTTP 응답 코드 추출
            java.util.function.Consumer<Object> onNext = response -> {
                try {
                    Object statusCode = response.getClass().getMethod("statusCode").invoke(response);
                    if (statusCode != null) {
                        int code = (int) statusCode.getClass().getMethod("value").invoke(statusCode);
                        state.span.setAttribute("http.response.status_code", (long) code);
                        if (code >= 400) state.span.setStatus(SpanStatus.ERROR, "HTTP " + code);
                    }
                } catch (Throwable ignored) {}
            };
            Object m2 = m1.getClass()
                    .getMethod("doOnNext", java.util.function.Consumer.class)
                    .invoke(m1, onNext);

            // doOnError: 오류 기록
            java.util.function.Consumer<Throwable> onError = e -> {
                if (e != null) {
                    try {
                        state.span.recordException(e);
                        state.span.setStatus(SpanStatus.ERROR, e.getMessage());
                    } catch (Throwable ignored) {}
                }
            };
            Object m3 = m2.getClass()
                    .getMethod("doOnError", java.util.function.Consumer.class)
                    .invoke(m2, onError);

            // doFinally: span.end() — complete/error/cancel 모두 처리
            java.util.function.Consumer<Object> onFinally = signalType -> {
                try { state.span.end(); } catch (Throwable ignored) {}
            };
            Object m4 = m3.getClass()
                    .getMethod("doFinally", java.util.function.Consumer.class)
                    .invoke(m3, onFinally);

            return m4;
        } catch (Throwable t) {
            AgentLogger.debug("[WebClient] Mono 래핑 실패: " + t.getMessage());
            return null;
        }
    }

    /**
     * ClientRequest에 traceparent 헤더를 추가한 새 ClientRequest를 빌드한다.
     * ClientRequest.from(original) → Builder → header() → build()
     *
     * ClientRequest 클래스 및 메서드 객체는 첫 호출 시 캐싱 (DCL 패턴).
     */
    private static Object injectTraceparent(Object request, String traceparent) {
        try {
            Class<?> clientRequestClass = CLIENT_REQUEST_CLASS;
            java.lang.reflect.Method fromMethod = FROM_METHOD;

            if (clientRequestClass == null) {
                ClassLoader cl = request.getClass().getClassLoader();
                if (cl == null) cl = ClassLoader.getSystemClassLoader();
                clientRequestClass = Class.forName(
                        "org.springframework.web.reactive.function.client.ClientRequest", false, cl);
                CLIENT_REQUEST_CLASS = clientRequestClass;
                fromMethod = clientRequestClass.getMethod("from", clientRequestClass);
                FROM_METHOD = fromMethod;
            }

            Object builder = fromMethod.invoke(null, request);

            java.lang.reflect.Method headerMethod = HEADER_METHOD;
            if (headerMethod == null) {
                headerMethod = builder.getClass().getMethod("header", String.class, String[].class);
                HEADER_METHOD = headerMethod;
            }
            builder = headerMethod.invoke(builder, "traceparent", new String[]{traceparent});

            java.lang.reflect.Method buildMethod = BUILD_METHOD;
            if (buildMethod == null) {
                buildMethod = builder.getClass().getMethod("build");
                BUILD_METHOD = buildMethod;
            }
            return buildMethod.invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String buildTraceparent(SpanContext ctx) {
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId()
                + "-" + HEX_BYTES[ctx.getTraceFlags().asByte() & 0xFF];
    }

    private static void restoreMdc(State state) {
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

    private WebClientAdvice() {}
}
