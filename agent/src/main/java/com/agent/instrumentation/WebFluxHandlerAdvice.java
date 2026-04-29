package com.agent.instrumentation;

import com.agent.concurrent.ReactorContextPropagator;
import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanBuilder;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * Spring WebFlux DispatcherHandler 계측.
 *
 * 대상: org.springframework.web.reactive.DispatcherHandler.handle(ServerWebExchange, Object)
 *
 * === Reactor 비동기 span 라이프사이클 ===
 *
 * handle()은 Mono<Void>를 즉시 반환한다 — 실제 처리는 구독(subscribe) 이후에 일어난다.
 * onExit에서 span을 즉시 종료하면 span lifetime이 Mono 구성 시간만 캡처하는 버그가 생긴다.
 *
 * 수정 후 흐름:
 *  1. onEnter  : span 생성 + ThreadLocal(scope) + MDC 설정
 *  2. handle() : Mono 파이프라인 구성 (즉시 반환)
 *  3. onExit   : MDC 즉시 복원, scope는 열린 채로 유지 (Reactor Schedulers 훅이 캡처 가능)
 *               반환된 Mono를 wrapMonoWithLifecycle()로 감싼다
 *  4. 구독 시  : doOnSubscribe → scope.close() (Netty 스레드 ThreadLocal 정리)
 *  5. operator : Schedulers 훅이 publishOn/subscribeOn 경계에서 span 전파
 *  6. Mono 종료 : doOnError → span 오류 기록, doFinally → span.end()
 *
 * === 한계 ===
 * - MDC(traceId/spanId)는 Netty 진입 스레드에서만 설정 후 즉시 복원된다.
 *   Reactor 스케줄러 스레드의 MDC는 ContextPropagatingRunnable이 span 정보를
 *   snapshot으로 캡처할 때 이미 복원된 이전 값으로 캡처된다.
 *   → 추후 Reactor contextWrite() 기반 전파로 개선 가능.
 */
public final class WebFluxHandlerAdvice {

    private static volatile Method GET_REQUEST_METHOD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) Object exchange,
            @Advice.Argument(1) Object handler) {

        // Reactor Schedulers 훅: 최초 WebFlux 요청 시 지연 설치
        ReactorContextPropagator.installIfNeeded();

        if (exchange == null) return null;

        SpanContext remoteParent = null;
        String httpMethod = null;
        String path = null;

        try {
            Object request = getRequest(exchange);
            if (request != null) {
                httpMethod = extractHttpMethod(request);
                path = extractPath(request);
                remoteParent = extractRemoteParent(request);
            }
        } catch (Throwable ignored) {}

        String spanName;
        if (httpMethod != null && path != null) {
            spanName = httpMethod + " " + path;
        } else if (httpMethod != null) {
            spanName = httpMethod + " /";
        } else {
            spanName = "WebFlux handler";
        }

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.webflux");
        SpanBuilder builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.SERVER);
        if (remoteParent != null) {
            builder.setParent(remoteParent);
        }
        Span span = builder.startSpan();

        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (path != null)       span.setAttribute("url.path", path);
        span.setAttribute("http.framework", "spring-webflux");

        if (handler != null) {
            try {
                Method getBeanType = handler.getClass().getMethod("getBeanType");
                Class<?> beanType = (Class<?>) getBeanType.invoke(handler);
                Method handlerMethod = (Method) handler.getClass().getMethod("getMethod").invoke(handler);
                span.setAttribute("http.route",
                        beanType.getSimpleName() + "#" + handlerMethod.getName());
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

        AgentLogger.debug("[WebFlux] span started: " + spanName
                + (remoteParent != null ? " (propagated)" : ""));
        return new State(span, scope, prevTraceId, prevSpanId);
    }

    /**
     * onExit 변경사항:
     * - @Advice.Return(readOnly=false) 로 반환된 Mono를 캡처하여 라이프사이클 래퍼로 교체
     * - 동기 오류 또는 null Mono: 즉시 scope.close() + span.end()
     * - 정상 Mono 반환: MDC만 즉시 복원, scope는 doOnSubscribe까지 열어둔다
     */
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

        // MDC를 Netty 스레드에서 즉시 복원한다.
        // scope는 열린 채로 유지: Reactor Schedulers 훅이 publishOn 시 span을 ThreadLocal에서 캡처한다.
        restoreMdc(state);

        Object wrapped = wrapMonoWithLifecycle(returnMono, state);
        if (wrapped != null) {
            returnMono = wrapped;
        } else {
            // 래핑 실패 시 폴백: 즉시 정리
            state.scope.close();
            state.span.end();
        }
    }

    /**
     * 반환된 Mono에 span 라이프사이클 훅을 추가한다.
     *
     * doOnSubscribe  : scope.close() — Netty 스레드의 ThreadLocal 정리 (구독 시점)
     * doOnError      : span 오류 기록
     * doFinally      : span.end() — complete / error / cancel 모두 처리
     *
     * 람다 본문은 에이전트 클래스로더 타입(Scope, Span, SpanStatus)만 사용하므로
     * 크로스 클래스로더 이슈가 없다. Reactor는 Consumer 인터페이스로만 호출한다.
     */
    private static Object wrapMonoWithLifecycle(Object mono, final State state) {
        try {
            java.util.function.Consumer<Object> onSubscribe = subscriber -> {
                try { state.scope.close(); } catch (Throwable ignored) {}
            };
            Object m1 = mono.getClass()
                    .getMethod("doOnSubscribe", java.util.function.Consumer.class)
                    .invoke(mono, onSubscribe);

            java.util.function.Consumer<Throwable> onError = e -> {
                if (e != null) {
                    try {
                        state.span.recordException(e);
                        state.span.setStatus(SpanStatus.ERROR, e.getMessage());
                    } catch (Throwable ignored) {}
                }
            };
            Object m2 = m1.getClass()
                    .getMethod("doOnError", java.util.function.Consumer.class)
                    .invoke(m1, onError);

            java.util.function.Consumer<Object> onFinally = signalType -> {
                try { state.span.end(); } catch (Throwable ignored) {}
            };
            return m2.getClass()
                    .getMethod("doFinally", java.util.function.Consumer.class)
                    .invoke(m2, onFinally);

        } catch (Throwable t) {
            AgentLogger.debug("[WebFlux] Mono 라이프사이클 래핑 실패: " + t.getMessage());
            return null;
        }
    }

    private static void restoreMdc(State state) {
        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    // --- reflection 헬퍼 ---

    private static Object getRequest(Object exchange) throws Exception {
        if (GET_REQUEST_METHOD == null) {
            GET_REQUEST_METHOD = exchange.getClass().getMethod("getRequest");
        }
        return GET_REQUEST_METHOD.invoke(exchange);
    }

    private static String extractHttpMethod(Object request) {
        try {
            Object httpMethod = request.getClass().getMethod("getMethod").invoke(request);
            return httpMethod == null ? null : httpMethod.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractPath(Object request) {
        try {
            Object uri = request.getClass().getMethod("getURI").invoke(request);
            if (uri == null) return null;
            return (String) uri.getClass().getMethod("getPath").invoke(uri);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SpanContext extractRemoteParent(Object request) {
        try {
            Object headers = request.getClass().getMethod("getHeaders").invoke(request);
            if (headers == null) return null;
            String traceparent = (String) headers.getClass()
                    .getMethod("getFirst", String.class).invoke(headers, "traceparent");
            if (traceparent == null || traceparent.isEmpty()) return null;

            java.util.Map<String, String> carrierMap = new java.util.HashMap<>(2);
            carrierMap.put("traceparent", traceparent);

            String tracestate = (String) headers.getClass()
                    .getMethod("getFirst", String.class).invoke(headers, "tracestate");
            if (tracestate != null) carrierMap.put("tracestate", tracestate);

            SpanContext extracted = com.agent.propagation.TraceContextPropagator.extractStatic(
                    carrierMap, new com.agent.propagation.MapTextMapGetter());
            return extracted.isValid() ? extracted : null;
        } catch (Throwable t) {
            return null;
        }
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

    private WebFluxHandlerAdvice() {}
}
