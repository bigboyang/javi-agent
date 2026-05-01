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

import java.lang.reflect.Method;

/**
 * ByteBuddy advice for Spring MVC controller methods.
 *
 * 스팬 이름: "METHOD /route-template" — Spring의 bestMatchingPattern으로 URL 정규화
 *   (예: "GET /users/{id}" — 고카디널리티 방지)
 * http.response.status_code: onExit에서 HttpServletResponse.getStatus()로 추출.
 * http.route / http.scheme / http.host: OTel HTTP 시맨틱 속성 추가.
 *
 * Reflection caching design
 * ─────────────────────────
 * ByteBuddy inlines @Advice method bodies into the target (app) class, so
 * Class.forName() inside those bodies runs with the app classloader — which
 * has Spring on its classpath. The static fields below live in the agent
 * classloader and are visible to the inlined code. Cache initialisation uses
 * a double-checked volatile pattern and MUST happen directly inside the
 * @Advice method bodies; helper methods run in the agent classloader context
 * where Spring classes are not visible.
 */
public final class ControllerMethodAdvice {

    private static final String SERVICE_NAME = com.agent.config.AgentConfig.get().getServiceName();
    // Spring MVC가 매칭된 라우트 패턴을 request attribute에 저장하는 키
    private static final String BEST_MATCHING_PATTERN_ATTR =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    // ── 메트릭 직접 레퍼런스 캐시 ("method|route" → Counter/Histogram) ────────
    // 매 요청마다 HashMap + MetricKey + TreeMap 생성을 제거한다 (Datadog 동일 패턴).
    private static final java.util.concurrent.ConcurrentHashMap<String, com.agent.metric.Counter>
            HTTP_COUNT_CACHE     = new java.util.concurrent.ConcurrentHashMap<>(32);
    private static final java.util.concurrent.ConcurrentHashMap<String, com.agent.metric.ExplicitBucketHistogram>
            HTTP_DUR_CACHE       = new java.util.concurrent.ConcurrentHashMap<>(32);
    private static final java.util.concurrent.ConcurrentHashMap<String, com.agent.metric.Counter>
            HTTP_ERR_COUNT_CACHE = new java.util.concurrent.ConcurrentHashMap<>(32);

    // ── Cached Class ──────────────────────────────────────────────────────────
    // public: ByteBuddy inlines advice into TestController (loaded by LaunchedClassLoader).
    // private fields throw IllegalAccessError from cross-classloader inlined bytecode.
    public static volatile Class<?> cachedRchClass;        // RequestContextHolder

    // ── Cached Methods on RequestContextHolder ────────────────────────────────
    public static volatile Method cachedGetRequestAttributes; // RCH.getRequestAttributes()

    // ── Cached Methods on ServletRequestAttributes (attrs object) ────────────
    public static volatile Method cachedGetRequest;           // attrs.getRequest()
    public static volatile Method cachedGetResponse;          // attrs.getResponse()

    // ── Cached Methods on HttpServletRequest (req object) ────────────────────
    public static volatile Method cachedReqGetMethod;         // req.getMethod()
    public static volatile Method cachedGetRequestURI;        // req.getRequestURI()
    public static volatile Method cachedGetAttribute;         // req.getAttribute(String)
    public static volatile Method cachedGetScheme;            // req.getScheme()
    public static volatile Method cachedGetHeader;            // req.getHeader(String)
    public static volatile Method cachedGetServerName;        // req.getServerName()
    public static volatile Method cachedGetServerPort;        // req.getServerPort()

    // ── Cached Methods on HttpServletResponse (response object) ──────────────
    public static volatile Method cachedGetStatus;            // response.getStatus()

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName) {

        com.agent.config.RemoteConfig remoteConfig = com.agent.config.RemoteConfigHolder.get();
        if (!remoteConfig.getServiceDisable().isEmpty()
                && remoteConfig.getServiceDisable().contains(SERVICE_NAME)) {
            return null;
        }

        // ── Layer 2 enrichment path ───────────────────────────────────────────
        // HttpServletAdvice(Layer 1)가 이미 SERVER span을 생성한 경우:
        // - 새 span을 생성하지 않고 기존 span에 http.route만 추가한다.
        // - RequestContextHolder에 의존하지 않으므로 null 취약점 없음.
        // - JDBC 등 child span은 servlet span의 자식이 된다 (이미 makeCurrent).
        HttpServletAdvice.State servletState = HttpServletAdvice.ACTIVE_SERVLET_STATE.get();
        if (servletState != null) {
            String route = null;
            Object req   = servletState.request;
            if (req != null) {
                try {
                    Method mGetAttribute = cachedGetAttribute;
                    if (mGetAttribute == null) {
                        synchronized (ControllerMethodAdvice.class) {
                            mGetAttribute = cachedGetAttribute;
                            if (mGetAttribute == null) {
                                mGetAttribute = req.getClass().getMethod("getAttribute", String.class);
                                cachedGetAttribute = mGetAttribute;
                            }
                        }
                    }
                    Object pattern = mGetAttribute.invoke(req, BEST_MATCHING_PATTERN_ATTR);
                    if (pattern != null) route = pattern.toString();
                } catch (Throwable ignored) {}
            }
            if (route != null) {
                String newSpanName = (servletState.httpMethod != null ? servletState.httpMethod : "HTTP")
                        + " " + route;
                servletState.span.updateName(newSpanName);
                servletState.span.setAttribute("http.route", route);
            }
            AgentLogger.debug("[MVC] servlet span enriched: route=" + route);
            // owned=false: scope/span 종료는 HttpServletAdvice가 담당
            return new State(servletState.span, null, null, null,
                    route, servletState.httpMethod, servletState.startNano, false);
        }
        // ─────────────────────────────────────────────────────────────────────

        String spanName;
        String httpMethod = null;
        String uri        = null;   // raw URI (http.target)
        String route      = null;   // template (http.route) — low-cardinality span name
        String scheme     = null;
        String host       = null;
        SpanContext remoteParent = null;

        // req is hoisted to this scope so the custom-headers block can reuse it
        // without a second RequestContextHolder lookup.
        Object req = null;

        try {
            // ── Class.forName cache (runs in app classloader via ByteBuddy inline) ──
            Class<?> rch = cachedRchClass;
            if (rch == null) {
                synchronized (ControllerMethodAdvice.class) {
                    rch = cachedRchClass;
                    if (rch == null) {
                        rch = Class.forName(
                                "org.springframework.web.context.request.RequestContextHolder");
                        cachedRchClass = rch;
                    }
                }
            }

            // ── getRequestAttributes() method cache ───────────────────────────────
            Method mGetRequestAttributes = cachedGetRequestAttributes;
            if (mGetRequestAttributes == null) {
                synchronized (ControllerMethodAdvice.class) {
                    mGetRequestAttributes = cachedGetRequestAttributes;
                    if (mGetRequestAttributes == null) {
                        mGetRequestAttributes = rch.getMethod("getRequestAttributes");
                        cachedGetRequestAttributes = mGetRequestAttributes;
                    }
                }
            }

            Object attrs = mGetRequestAttributes.invoke(null);
            if (attrs != null) {

                // ── getRequest() method cache ─────────────────────────────────────
                Method mGetRequest = cachedGetRequest;
                if (mGetRequest == null) {
                    synchronized (ControllerMethodAdvice.class) {
                        mGetRequest = cachedGetRequest;
                        if (mGetRequest == null) {
                            mGetRequest = attrs.getClass().getMethod("getRequest");
                            cachedGetRequest = mGetRequest;
                        }
                    }
                }
                req = mGetRequest.invoke(attrs);

                if (req != null) {

                    // ── req.getMethod() cache ─────────────────────────────────────
                    Method mReqGetMethod = cachedReqGetMethod;
                    if (mReqGetMethod == null) {
                        synchronized (ControllerMethodAdvice.class) {
                            mReqGetMethod = cachedReqGetMethod;
                            if (mReqGetMethod == null) {
                                mReqGetMethod = req.getClass().getMethod("getMethod");
                                cachedReqGetMethod = mReqGetMethod;
                            }
                        }
                    }
                    httpMethod = (String) mReqGetMethod.invoke(req);

                    // ── req.getRequestURI() cache ─────────────────────────────────
                    Method mGetRequestURI = cachedGetRequestURI;
                    if (mGetRequestURI == null) {
                        synchronized (ControllerMethodAdvice.class) {
                            mGetRequestURI = cachedGetRequestURI;
                            if (mGetRequestURI == null) {
                                mGetRequestURI = req.getClass().getMethod("getRequestURI");
                                cachedGetRequestURI = mGetRequestURI;
                            }
                        }
                    }
                    uri = (String) mGetRequestURI.invoke(req);

                    // URL 정규화: bestMatchingPattern → "/users/{id}" 형식
                    try {
                        Method mGetAttribute = cachedGetAttribute;
                        if (mGetAttribute == null) {
                            synchronized (ControllerMethodAdvice.class) {
                                mGetAttribute = cachedGetAttribute;
                                if (mGetAttribute == null) {
                                    mGetAttribute = req.getClass().getMethod("getAttribute", String.class);
                                    cachedGetAttribute = mGetAttribute;
                                }
                            }
                        }
                        Object pattern = mGetAttribute.invoke(req, BEST_MATCHING_PATTERN_ATTR);
                        if (pattern != null) route = pattern.toString();
                    } catch (Throwable ignored) {}

                    // http.scheme
                    try {
                        Method mGetScheme = cachedGetScheme;
                        if (mGetScheme == null) {
                            synchronized (ControllerMethodAdvice.class) {
                                mGetScheme = cachedGetScheme;
                                if (mGetScheme == null) {
                                    mGetScheme = req.getClass().getMethod("getScheme");
                                    cachedGetScheme = mGetScheme;
                                }
                            }
                        }
                        scheme = (String) mGetScheme.invoke(req);
                    } catch (Throwable ignored) {}

                    // http.host — Host 헤더 우선, 없으면 serverName:port 조합
                    try {
                        Method mGetHeader = cachedGetHeader;
                        if (mGetHeader == null) {
                            synchronized (ControllerMethodAdvice.class) {
                                mGetHeader = cachedGetHeader;
                                if (mGetHeader == null) {
                                    mGetHeader = req.getClass().getMethod("getHeader", String.class);
                                    cachedGetHeader = mGetHeader;
                                }
                            }
                        }
                        host = (String) mGetHeader.invoke(req, "Host");
                        if (host == null) {
                            Method mGetServerName = cachedGetServerName;
                            if (mGetServerName == null) {
                                synchronized (ControllerMethodAdvice.class) {
                                    mGetServerName = cachedGetServerName;
                                    if (mGetServerName == null) {
                                        mGetServerName = req.getClass().getMethod("getServerName");
                                        cachedGetServerName = mGetServerName;
                                    }
                                }
                            }
                            Method mGetServerPort = cachedGetServerPort;
                            if (mGetServerPort == null) {
                                synchronized (ControllerMethodAdvice.class) {
                                    mGetServerPort = cachedGetServerPort;
                                    if (mGetServerPort == null) {
                                        mGetServerPort = req.getClass().getMethod("getServerPort");
                                        cachedGetServerPort = mGetServerPort;
                                    }
                                }
                            }
                            String serverName = (String) mGetServerName.invoke(req);
                            int serverPort    = (int)    mGetServerPort.invoke(req);
                            host = (serverPort == 80 || serverPort == 443)
                                    ? serverName
                                    : serverName + ":" + serverPort;
                        }
                    } catch (Throwable ignored) {}

                    // span
                    // 스팬 이름: route 템플릿 우선, 없으면 raw URI
                    spanName = httpMethod + " " + (route != null ? route : uri);

                    // W3C traceparent & baggage 추출
                    // traceparent가 없는 요청(대부분)은 HashMap 할당 자체를 건너뜀 (fast-path).
                    try {
                        Method mGetHeader = cachedGetHeader; // already populated above
                        String traceparent = (String) mGetHeader.invoke(req, "traceparent");
                        String baggageHeader = (String) mGetHeader.invoke(req, "baggage");

                        if (traceparent != null || baggageHeader != null) {
                            // 전파 헤더가 있을 때만 Map 할당
                            java.util.Map<String, String> headers = new java.util.HashMap<>(4);
                            if (traceparent != null) headers.put("traceparent", traceparent);

                            if (traceparent != null) {
                                String tracestate = (String) mGetHeader.invoke(req, "tracestate");
                                if (tracestate != null) headers.put("tracestate", tracestate);
                            }
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
                        }
                    } catch (Throwable ignored) {}

                } else {
                    int dot = typeName.lastIndexOf('.');
                    spanName = (dot >= 0 ? typeName.substring(dot + 1) : typeName) + "#" + methodName;
                }
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
        if (uri != null)        span.setAttribute("url.path", uri);
        if (route != null)      span.setAttribute("http.route", route);
        if (scheme != null)     span.setAttribute("url.scheme", scheme);
        if (host != null) {
            // "hostname:port" → server.address + server.port
            int colon = host.lastIndexOf(':');
            if (colon > 0 && !host.startsWith("[")) {
                try {
                    span.setAttribute("server.port", Long.parseLong(host.substring(colon + 1)));
                    span.setAttribute("server.address", host.substring(0, colon));
                } catch (NumberFormatException e) {
                    span.setAttribute("server.address", host);
                }
            } else {
                span.setAttribute("server.address", host);
            }
        }

        // 원격 설정에서 지정한 커스텀 헤더 캡처
        // Reuses the req object already resolved in the main block above — no
        // second RequestContextHolder lookup needed.
        java.util.List<String> customHeaders = remoteConfig.getCustomHeaders();
        if (!customHeaders.isEmpty() && req != null) {
            try {
                // cachedGetHeader is guaranteed to be populated if req != null,
                // since the main block above initialised it before we reach here.
                Method mGetHeader = cachedGetHeader;
                for (String header : customHeaders) {
                    if (HeaderSanitizer.isSensitive(header)) continue;
                    try {
                        String val = (String) mGetHeader.invoke(req, header);
                        if (val != null) {
                            span.setAttribute("http.request.header." + header.toLowerCase(), val);
                        }
                    } catch (Throwable ignored) {}
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
                route != null ? route : uri, httpMethod, System.nanoTime(), true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;

        // ── Layer 2 (servlet span enrichment) 경로 ──────────────────────────
        // owned=false: span 소유권이 HttpServletAdvice에 있으므로
        //   scope/span 종료, status_code 설정, MDC 복원은 HttpServletAdvice.onExit가 담당.
        //   여기서는 metrics 기록만 수행한다.
        if (!state.owned) {
            if (error != null) {
                state.span.recordException(error);
                state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            }
            // metric만 기록
            recordHttpMetrics(state, error);
            return;
        }
        // ────────────────────────────────────────────────────────────────────

        // http.response.status_code: HttpServletResponse.getStatus()로 추출
        // Reuses cachedRchClass / cachedGetRequestAttributes populated by onEnter.
        try {
            Class<?> rch = cachedRchClass;
            if (rch == null) {
                synchronized (ControllerMethodAdvice.class) {
                    rch = cachedRchClass;
                    if (rch == null) {
                        rch = Class.forName(
                                "org.springframework.web.context.request.RequestContextHolder");
                        cachedRchClass = rch;
                    }
                }
            }

            Method mGetRequestAttributes = cachedGetRequestAttributes;
            if (mGetRequestAttributes == null) {
                synchronized (ControllerMethodAdvice.class) {
                    mGetRequestAttributes = cachedGetRequestAttributes;
                    if (mGetRequestAttributes == null) {
                        mGetRequestAttributes = rch.getMethod("getRequestAttributes");
                        cachedGetRequestAttributes = mGetRequestAttributes;
                    }
                }
            }

            Object attrs = mGetRequestAttributes.invoke(null);
            if (attrs != null) {

                // ── getResponse() method cache ────────────────────────────────────
                Method mGetResponse = cachedGetResponse;
                if (mGetResponse == null) {
                    synchronized (ControllerMethodAdvice.class) {
                        mGetResponse = cachedGetResponse;
                        if (mGetResponse == null) {
                            mGetResponse = attrs.getClass().getMethod("getResponse");
                            cachedGetResponse = mGetResponse;
                        }
                    }
                }

                Object response = mGetResponse.invoke(attrs);
                if (response != null) {

                    // ── response.getStatus() method cache ─────────────────────────
                    Method mGetStatus = cachedGetStatus;
                    if (mGetStatus == null) {
                        synchronized (ControllerMethodAdvice.class) {
                            mGetStatus = cachedGetStatus;
                            if (mGetStatus == null) {
                                mGetStatus = response.getClass().getMethod("getStatus");
                                cachedGetStatus = mGetStatus;
                            }
                        }
                    }

                    int statusCode = (int) mGetStatus.invoke(response);
                    state.span.setAttribute("http.response.status_code", (long) statusCode);
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

        // HTTP 요청 메트릭 기록 — scope.close()/span.end() 이전에 호출해야
        // Exemplar 샘플링이 Context.currentSpan()으로 traceId/spanId를 캡처할 수 있다.
        recordHttpMetrics(state, error);

        state.scope.close();
        state.span.end();

        if (state.prevTraceId == null) MDC.remove("traceId"); else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId  == null) MDC.remove("spanId");  else MDC.put("spanId",  state.prevSpanId);
    }

    private static void recordHttpMetrics(State state, Throwable error) {
        try {
            if (state.route == null) return;
            double durationSeconds = (System.nanoTime() - state.startNano) / 1_000_000_000.0;
            final String method   = state.httpMethod != null ? state.httpMethod : "UNKNOWN";
            final String route    = state.route;
            final String cacheKey = method + "|" + route;

            // Counter/Histogram 레퍼런스를 직접 캐싱 — HashMap+MetricKey+TreeMap 생성 제거
            HTTP_COUNT_CACHE.computeIfAbsent(cacheKey, k -> {
                java.util.Map<String, String> tags = new java.util.HashMap<>(4);
                tags.put("http.request.method", method);
                tags.put("http.route", route);
                return com.agent.metric.MetricRegistry.get().counter("http.server.request.count", tags);
            }).increment();

            HTTP_DUR_CACHE.computeIfAbsent(cacheKey, k -> {
                java.util.Map<String, String> tags = new java.util.HashMap<>(4);
                tags.put("http.request.method", method);
                tags.put("http.route", route);
                return com.agent.metric.MetricRegistry.get().histogram(
                        "http.server.request.duration", "", "s", tags,
                        com.agent.metric.ExplicitBucketHistogram.OTEL_HTTP_BOUNDARIES_SECONDS);
            }).record(durationSeconds);

            if (error != null) {
                HTTP_ERR_COUNT_CACHE.computeIfAbsent(cacheKey, k -> {
                    java.util.Map<String, String> tags = new java.util.HashMap<>(4);
                    tags.put("http.request.method", method);
                    tags.put("http.route", route);
                    return com.agent.metric.MetricRegistry.get().counter("http.server.request.error.count", tags);
                }).increment();
            }
        } catch (Throwable ignored) {}
    }

    public static final class State {
        public final Span    span;
        public final Scope   scope;
        public final String  prevTraceId;
        public final String  prevSpanId;
        public final String  route;
        public final String  httpMethod;
        public final long    startNano;
        /**
         * true  — 이 State가 span을 소유 (onExit에서 scope.close() + span.end() + MDC 복원)
         * false — HttpServletAdvice(Layer 1)가 span을 소유 (metrics 기록만 수행)
         */
        public final boolean owned;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId,
                     String route, String httpMethod, long startNano, boolean owned) {
            this.span        = span;
            this.scope       = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId  = prevSpanId;
            this.route       = route;
            this.httpMethod  = httpMethod;
            this.startNano   = startNano;
            this.owned       = owned;
        }
    }

    private ControllerMethodAdvice() {}
}
