package com.agent.instrumentation;

import com.agent.common.utils.HeaderSanitizer;
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

/**
 * ByteBuddy advice for Spring MVC controller methods.
 *
 * 스팬 이름: "METHOD /route-template" — Spring의 bestMatchingPattern으로 URL 정규화
 *   (예: "GET /users/{id}" — 고카디널리티 방지)
 * http.response.status_code: onExit에서 HttpServletResponse.getStatus()로 추출.
 * http.route / http.scheme / http.host: OTel HTTP 시맨틱 속성 추가.
 */
public final class ControllerMethodAdvice {

    private static final String SERVICE_NAME = com.agent.config.AgentConfig.load().getServiceName();
    // Spring MVC가 매칭된 라우트 패턴을 request attribute에 저장하는 키
    private static final String BEST_MATCHING_PATTERN_ATTR =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName) {

        com.agent.config.RemoteConfig remoteConfig = com.agent.config.RemoteConfigHolder.get();
        if (!remoteConfig.getServiceDisable().isEmpty()
                && remoteConfig.getServiceDisable().contains(SERVICE_NAME)) {
            return null;
        }

        String spanName;
        String httpMethod = null;
        String uri        = null;   // raw URI (http.target)
        String route      = null;   // template (http.route) — low-cardinality span name
        String scheme     = null;
        String host       = null;
        SpanContext remoteParent = null;

        try {
            Class<?> rch = Class.forName(
                    "org.springframework.web.context.request.RequestContextHolder");
            Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
            if (attrs != null) {
                Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
                httpMethod = (String) req.getClass().getMethod("getMethod").invoke(req);
                uri        = (String) req.getClass().getMethod("getRequestURI").invoke(req);

                // URL 정규화: bestMatchingPattern → "/users/{id}" 형식
                try {
                    Object pattern = req.getClass()
                            .getMethod("getAttribute", String.class)
                            .invoke(req, BEST_MATCHING_PATTERN_ATTR);
                    if (pattern != null) route = pattern.toString();
                } catch (Throwable ignored) {}

                // http.scheme
                try {
                    scheme = (String) req.getClass().getMethod("getScheme").invoke(req);
                } catch (Throwable ignored) {}

                // http.host — Host 헤더 우선, 없으면 serverName:port 조합
                try {
                    host = (String) req.getClass()
                            .getMethod("getHeader", String.class).invoke(req, "Host");
                    if (host == null) {
                        String serverName = (String) req.getClass()
                                .getMethod("getServerName").invoke(req);
                        int serverPort = (int) req.getClass()
                                .getMethod("getServerPort").invoke(req);
                        host = (serverPort == 80 || serverPort == 443)
                                ? serverName
                                : serverName + ":" + serverPort;
                    }
                } catch (Throwable ignored) {}

                // 스팬 이름: route 템플릿 우선, 없으면 raw URI
                spanName = httpMethod + " " + (route != null ? route : uri);

                // W3C traceparent & baggage 추출
                try {
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    String traceparent = (String) req.getClass()
                            .getMethod("getHeader", String.class).invoke(req, "traceparent");
                    if (traceparent != null) headers.put("traceparent", traceparent);

                    String tracestate = (String) req.getClass()
                            .getMethod("getHeader", String.class).invoke(req, "tracestate");
                    if (tracestate != null) headers.put("tracestate", tracestate);

                    String baggageHeader = (String) req.getClass()
                            .getMethod("getHeader", String.class).invoke(req, "baggage");
                    if (baggageHeader != null) headers.put("baggage", baggageHeader);

                    if (traceparent != null) {
                        com.agent.span.SpanContext extracted =
                                com.agent.propagation.TraceContextPropagator.extractStatic(
                                        headers, new com.agent.propagation.MapTextMapGetter());
                        if (extracted.isValid()) remoteParent = extracted;
                    }

                    com.agent.propagation.Baggage baggage =
                            com.agent.propagation.ContextPropagators
                                    .getBaggagePropagator()
                                    .extract(headers, new com.agent.propagation.MapTextMapGetter());
                    if (!baggage.isEmpty()) {
                        com.agent.span.Context.makeCurrent(baggage);
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

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.spring.mvc");
        SpanBuilder builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.SERVER);
        if (remoteParent != null) builder.setParent(remoteParent);
        Span span = builder.startSpan();

        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (uri != null)        span.setAttribute("http.target", uri);
        if (route != null)      span.setAttribute("http.route", route);
        if (scheme != null)     span.setAttribute("http.scheme", scheme);
        if (host != null)       span.setAttribute("http.host", host);

        // 원격 설정에서 지정한 커스텀 헤더 캡처
        java.util.List<String> customHeaders = remoteConfig.getCustomHeaders();
        if (!customHeaders.isEmpty()) {
            try {
                Class<?> rch = Class.forName(
                        "org.springframework.web.context.request.RequestContextHolder");
                Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
                if (attrs != null) {
                    Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
                    for (String header : customHeaders) {
                        if (HeaderSanitizer.isSensitive(header)) continue;
                        try {
                            String val = (String) req.getClass()
                                    .getMethod("getHeader", String.class).invoke(req, header);
                            if (val != null) {
                                span.setAttribute("http.request.header." + header.toLowerCase(), val);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId  = MDC.get("spanId");
        SpanContext spanCtx = span.getContext();
        if (spanCtx != null && spanCtx.isValid()) {
            MDC.put("traceId", spanCtx.getTraceId());
            MDC.put("spanId",  spanCtx.getSpanId());
        }

        AgentLogger.debug("[HTTP] span started: " + spanName
                + (remoteParent != null ? " (propagated traceId=" + remoteParent.getTraceId() + ")" : ""));
        return new State(span, scope, prevTraceId, prevSpanId,
                route != null ? route : uri, httpMethod, System.nanoTime());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;

        // http.response.status_code: HttpServletResponse.getStatus()로 추출
        try {
            Class<?> rch = Class.forName(
                    "org.springframework.web.context.request.RequestContextHolder");
            Object attrs = rch.getMethod("getRequestAttributes").invoke(null);
            if (attrs != null) {
                Object response = attrs.getClass().getMethod("getResponse").invoke(attrs);
                if (response != null) {
                    int statusCode = (int) response.getClass().getMethod("getStatus").invoke(response);
                    state.span.setAttribute("http.response.status_code", String.valueOf(statusCode));
                    // 4xx/5xx — exception 없이도 에러로 표시 (error rate 계산용)
                    if (statusCode >= 400 && error == null) {
                        state.span.setStatus(SpanStatus.ERROR, "HTTP " + statusCode);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[HTTP] span error: " + error.getMessage());
        }
        state.scope.close();
        state.span.end();

        // HTTP 요청 메트릭 기록 (Span과 별개로 100% 집계)
        recordHttpMetrics(state, error);

        if (state.prevTraceId == null) MDC.remove("traceId"); else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId  == null) MDC.remove("spanId");  else MDC.put("spanId",  state.prevSpanId);
    }

    private static void recordHttpMetrics(State state, Throwable error) {
        try {
            if (state.route == null) return;
            long durationMs = (System.nanoTime() - state.startNano) / 1_000_000L;
            String method = state.httpMethod != null ? state.httpMethod : "UNKNOWN";

            // 요청 count 태그: method + route (카디널리티 제어)
            java.util.Map<String, String> countTags = new java.util.HashMap<>(4);
            countTags.put("http.request.method", method);
            countTags.put("http.route", state.route);

            com.agent.metric.MetricRegistry reg = com.agent.metric.MetricRegistry.get();
            reg.counter("http.server.request.count", countTags).increment();

            // latency 히스토그램 태그: method + route (status는 카디널리티가 낮으므로 추가 가능)
            java.util.Map<String, String> durTags = new java.util.HashMap<>(4);
            durTags.put("http.request.method", method);
            durTags.put("http.route", state.route);
            reg.histogram("http.server.request.duration", durTags).record(durationMs);

            // 에러 카운터 (5xx or exception)
            if (error != null) {
                reg.counter("http.server.request.error.count", countTags).increment();
            }
        } catch (Throwable ignored) {}
    }

    public static final class State {
        public final Span   span;
        public final Scope  scope;
        public final String prevTraceId;
        public final String prevSpanId;
        public final String route;
        public final String httpMethod;
        public final long   startNano;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId,
                     String route, String httpMethod, long startNano) {
            this.span        = span;
            this.scope       = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId  = prevSpanId;
            this.route       = route;
            this.httpMethod  = httpMethod;
            this.startNano   = startNano;
        }
    }

    private ControllerMethodAdvice() {}
}
