package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanBuilder;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * Spring WebFlux DispatcherHandler 계측.
 *
 * 대상: org.springframework.web.reactive.DispatcherHandler.handle(ServerWebExchange, Object)
 *
 * WebFlux 계측의 핵심 제약:
 * - Reactor의 실제 처리는 별도 스케줄러 스레드에서 일어나므로, ThreadLocal 기반 Context는
 *   handle() 진입 시점(Netty 이벤트 루프)의 span만 캡처한다.
 * - onEnter/onExit은 항상 같은 스레드에서 호출되므로 span 라이프사이클은 올바르게 관리된다.
 * - Reactor 파이프라인 내부(flatMap 등)의 추적이 필요하다면 별도로 ReactorContextPropagation을
 *   구현해야 하며, 이 Advice는 그 진입점(entry span)을 제공한다.
 *
 * W3C traceparent 추출:
 * - ServerWebExchange.getRequest().getHeaders() 를 reflection으로 접근한다.
 * - 헤더 접근 실패 시 새로운 root span을 생성한다 (분산 트레이싱 단절은 허용).
 *
 * Span 명: "METHOD /path" (예: "GET /api/users")
 * 경로를 얻지 못하면 "WebFlux handler"로 fallback.
 */
public final class WebFluxHandlerAdvice {

    // getRequest() reflection 메서드 캐싱 — 첫 호출 이후 재사용
    private static volatile Method GET_REQUEST_METHOD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) Object exchange,
            @Advice.Argument(1) Object handler) {

        if (exchange == null) return null;

        // W3C traceparent 추출 — 분산 트레이싱 컨텍스트 이어받기
        SpanContext remoteParent = null;
        String httpMethod = null;
        String path = null;

        try {
            // ServerWebExchange -> ServerHttpRequest -> URI/Headers
            Object request = getRequest(exchange);
            if (request != null) {
                httpMethod = extractHttpMethod(request);
                path = extractPath(request);
                remoteParent = extractRemoteParent(request);
            }
        } catch (Throwable ignored) {}

        // span name: "GET /api/orders" 형식. OTel HTTP 시맨틱 컨벤션 준수
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

        // OTel HTTP 시맨틱 속성 설정
        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (path != null)       span.setAttribute("http.target", path);
        span.setAttribute("http.framework", "spring-webflux");

        // handler에서 route 패턴 추출 시도 (HandlerMethod인 경우)
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

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Thrown Throwable error) {

        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();

        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    // --- reflection 헬퍼: Advice 클래스 내부 static 메서드는 Advice 바이트코드로 인라인되지 않으므로
    //     별도 메서드로 분리해 가독성 확보 (suppress=Throwable이 onEnter를 보호)

    private static Object getRequest(Object exchange) throws Exception {
        if (GET_REQUEST_METHOD == null) {
            GET_REQUEST_METHOD = exchange.getClass().getMethod("getRequest");
        }
        return GET_REQUEST_METHOD.invoke(exchange);
    }

    private static String extractHttpMethod(Object request) {
        try {
            // ServerHttpRequest.getMethod() -> HttpMethod enum
            Object httpMethod = request.getClass().getMethod("getMethod").invoke(request);
            return httpMethod == null ? null : httpMethod.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractPath(Object request) {
        try {
            // ServerHttpRequest.getURI().getPath()
            Object uri = request.getClass().getMethod("getURI").invoke(request);
            if (uri == null) return null;
            return (String) uri.getClass().getMethod("getPath").invoke(uri);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SpanContext extractRemoteParent(Object request) {
        try {
            // ServerHttpRequest.getHeaders().getFirst("traceparent")
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
