package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice for javax.servlet.http.HttpServlet.service(HttpServletRequest, HttpServletResponse).
 *
 * <h3>2-Layer HTTP Instrumentation — Layer 1 (Servlet)</h3>
 * <pre>
 * Layer 1 (THIS): HttpServlet.service()
 *   → SERVER span 생성, http.method/uri/scheme/host 설정, W3C 전파 추출
 *   → ACTIVE_SERVLET_STATE ThreadLocal에 상태 저장
 *
 * Layer 2: ControllerMethodAdvice
 *   → ACTIVE_SERVLET_STATE에서 request 직접 접근
 *   → bestMatchingPattern으로 http.route 추출 → span 이름/route 갱신
 *   → 새 span 생성 없음 (servlet span 보강만)
 * </pre>
 *
 * <h3>핵심 개선 사항</h3>
 * <ul>
 *   <li>RequestContextHolder 의존 제거 — @Advice.Argument(0)으로 request 직접 접근</li>
 *   <li>RequestContextHolder.getRequestAttributes() == null 취약점 완전 해소</li>
 *   <li>W3C traceparent 추출을 Servlet 레이어로 이동 (더 이른 시점)</li>
 *   <li>http.request.method, http.target, http.scheme, http.host 항상 설정</li>
 * </ul>
 *
 * <h3>Reflection caching design</h3>
 * ByteBuddy inlines @Advice method bodies into the target (app) class, so
 * Class.forName() inside those bodies runs with the app classloader — which
 * has Servlet API on its classpath. Static caches live in the agent classloader
 * and are visible to the inlined code. Double-checked volatile pattern used.
 */
public final class HttpServletAdvice {

    /**
     * 현재 스레드에서 활성화된 Servlet 스팬 상태.
     * ControllerMethodAdvice(Layer 2)가 이를 읽어 http.route를 보강한다.
     * Forward/Include 중첩 호출 방지에도 사용된다.
     */
    public static final ThreadLocal<State> ACTIVE_SERVLET_STATE = new ThreadLocal<>();

    // ── Reflection caches ──────────────────────────────────────────────────────
    // public: ByteBuddy inlines advice into FrameworkServlet (loaded by LaunchedClassLoader).
    // private fields throw IllegalAccessError from cross-classloader inlined bytecode.
    public static volatile Method cachedReqGetMethod;    // req.getMethod()
    public static volatile Method cachedGetRequestURI;   // req.getRequestURI()
    public static volatile Method cachedGetScheme;       // req.getScheme()
    public static volatile Method cachedGetHeader;       // req.getHeader(String)
    public static volatile Method cachedGetServerName;   // req.getServerName()
    public static volatile Method cachedGetServerPort;   // req.getServerPort()
    public static volatile Method cachedGetStatus;       // resp.getStatus()

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response) {

        // Forward/Include 등 중첩 servlet 호출 방지 — 외부 servlet span 재사용
        if (ACTIVE_SERVLET_STATE.get() != null) return null;

        String httpMethod = null;
        String uri        = null;
        String scheme     = null;
        String host       = null;
        com.agent.span.SpanContext remoteParent = null;

        try {
            Class<?> reqClass = request.getClass();

            // req.getMethod()
            Method mGetMethod = cachedReqGetMethod;
            if (mGetMethod == null) {
                synchronized (HttpServletAdvice.class) {
                    mGetMethod = cachedReqGetMethod;
                    if (mGetMethod == null) {
                        mGetMethod = reqClass.getMethod("getMethod");
                        cachedReqGetMethod = mGetMethod;
                    }
                }
            }
            httpMethod = (String) mGetMethod.invoke(request);

            // req.getRequestURI()
            Method mGetURI = cachedGetRequestURI;
            if (mGetURI == null) {
                synchronized (HttpServletAdvice.class) {
                    mGetURI = cachedGetRequestURI;
                    if (mGetURI == null) {
                        mGetURI = reqClass.getMethod("getRequestURI");
                        cachedGetRequestURI = mGetURI;
                    }
                }
            }
            uri = (String) mGetURI.invoke(request);

            // req.getScheme()
            try {
                Method mGetScheme = cachedGetScheme;
                if (mGetScheme == null) {
                    synchronized (HttpServletAdvice.class) {
                        mGetScheme = cachedGetScheme;
                        if (mGetScheme == null) {
                            mGetScheme = reqClass.getMethod("getScheme");
                            cachedGetScheme = mGetScheme;
                        }
                    }
                }
                scheme = (String) mGetScheme.invoke(request);
            } catch (Throwable ignored) {}

            // req.getHeader("Host") — Host 헤더 우선, 없으면 serverName:port 조합
            try {
                Method mGetHeader = cachedGetHeader;
                if (mGetHeader == null) {
                    synchronized (HttpServletAdvice.class) {
                        mGetHeader = cachedGetHeader;
                        if (mGetHeader == null) {
                            mGetHeader = reqClass.getMethod("getHeader", String.class);
                            cachedGetHeader = mGetHeader;
                        }
                    }
                }
                host = (String) mGetHeader.invoke(request, "Host");
                if (host == null) {
                    Method mGetServerName = cachedGetServerName;
                    if (mGetServerName == null) {
                        synchronized (HttpServletAdvice.class) {
                            mGetServerName = cachedGetServerName;
                            if (mGetServerName == null) {
                                mGetServerName = reqClass.getMethod("getServerName");
                                cachedGetServerName = mGetServerName;
                            }
                        }
                    }
                    Method mGetServerPort = cachedGetServerPort;
                    if (mGetServerPort == null) {
                        synchronized (HttpServletAdvice.class) {
                            mGetServerPort = cachedGetServerPort;
                            if (mGetServerPort == null) {
                                mGetServerPort = reqClass.getMethod("getServerPort");
                                cachedGetServerPort = mGetServerPort;
                            }
                        }
                    }
                    String serverName = (String) mGetServerName.invoke(request);
                    int    serverPort = (int)    mGetServerPort.invoke(request);
                    host = (serverPort == 80 || serverPort == 443)
                            ? serverName
                            : serverName + ":" + serverPort;
                }

                // W3C traceparent 추출 (mGetHeader 이미 캐싱됨)
                String traceparent = (String) mGetHeader.invoke(request, "traceparent");
                if (traceparent != null) {
                    java.util.Map<String, String> headers = new java.util.HashMap<>(4);
                    headers.put("traceparent", traceparent);
                    String tracestate = (String) mGetHeader.invoke(request, "tracestate");
                    if (tracestate != null) headers.put("tracestate", tracestate);
                    com.agent.span.SpanContext extracted =
                            com.agent.propagation.TraceContextPropagator.extractStatic(
                                    headers, new com.agent.propagation.MapTextMapGetter());
                    if (extracted.isValid()) remoteParent = extracted;
                }
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}

        // 초기 span 이름: "METHOD /uri" — ControllerMethodAdvice(Layer 2)가 route 확정 후 갱신
        String spanName = (httpMethod != null ? httpMethod : "HTTP")
                + " " + (uri != null ? uri : "/");

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.servlet");
        com.agent.span.SpanBuilder builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.SERVER);
        if (remoteParent != null) builder.setParent(remoteParent);
        Span span = builder.startSpan();

        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (uri != null)        span.setAttribute("url.path", uri);
        if (scheme != null)     span.setAttribute("url.scheme", scheme);
        if (host != null) {
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

        Scope scope = span.makeCurrent();

        // MDC 주입 — 로그에 traceId/spanId 자동 포함
        String prevTraceId = MDC.get("traceId");
        String prevSpanId  = MDC.get("spanId");
        com.agent.span.SpanContext spanCtx = span.getContext();
        if (spanCtx != null && spanCtx.isValid()) {
            MDC.put("traceId", spanCtx.getTraceId());
            MDC.put("spanId",  spanCtx.getSpanId());
        }

        State state = new State(span, scope, prevTraceId, prevSpanId, httpMethod, System.nanoTime(), request);
        ACTIVE_SERVLET_STATE.set(state);
        AgentLogger.debug("[Servlet] span started: " + spanName);
        return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Argument(1) Object response,
            @Advice.Thrown Throwable error) {

        if (state == null) return;
        ACTIVE_SERVLET_STATE.remove();

        // http.response.status_code: response.getStatus()
        try {
            Method mGetStatus = cachedGetStatus;
            if (mGetStatus == null) {
                synchronized (HttpServletAdvice.class) {
                    mGetStatus = cachedGetStatus;
                    if (mGetStatus == null) {
                        mGetStatus = response.getClass().getMethod("getStatus");
                        cachedGetStatus = mGetStatus;
                    }
                }
            }
            int statusCode = (int) mGetStatus.invoke(response);
            state.span.setAttribute("http.response.status_code", (long) statusCode);
            if (statusCode >= 400 && error == null) {
                state.span.setStatus(SpanStatus.ERROR, "HTTP " + statusCode);
            }
        } catch (Throwable ignored) {}

        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[Servlet] span error: " + error.getMessage());
        }

        state.scope.close();
        state.span.end();

        // MDC 복원
        if (state.prevTraceId == null) MDC.remove("traceId"); else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId  == null) MDC.remove("spanId");  else MDC.put("spanId",  state.prevSpanId);
    }

    public static final class State {
        public final Span   span;
        public final Scope  scope;
        public final String prevTraceId;
        public final String prevSpanId;
        public final String httpMethod;
        public final long   startNano;
        /** HttpServletRequest 참조 — ControllerMethodAdvice(Layer 2)가 bestMatchingPattern 추출에 사용 */
        public final Object request;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId,
                     String httpMethod, long startNano, Object request) {
            this.span        = span;
            this.scope       = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId  = prevSpanId;
            this.httpMethod  = httpMethod;
            this.startNano   = startNano;
            this.request     = request;
        }
    }

    private HttpServletAdvice() {}
}
