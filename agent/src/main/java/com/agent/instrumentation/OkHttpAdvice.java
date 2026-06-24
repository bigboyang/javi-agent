package com.agent.instrumentation;

import com.agent.common.utils.UrlSanitizer;
import com.agent.logs.AgentLogger;
import com.agent.span.Context;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

/**
 * OkHttp3 HTTP 클라이언트 계측.
 *
 * 대상: okhttp3.internal.connection.RealCall.execute()
 *       (OkHttp 4.x — Kotlin 기반이지만 바이트코드는 동일 클래스명)
 *
 * OkHttp 3.x에서는 클래스명이 okhttp3.RealCall 일 수 있으므로
 * 플러그인에서 두 클래스명을 모두 등록한다.
 *
 * Request 정보 추출 (@Advice.This 기반):
 *   - originalRequest 필드에서 URL, method 추출 (리플렉션)
 *
 * traceparent 주입:
 *   OkHttp Request는 불변이므로 request.newBuilder().header(...).build()로 새 Request 생성.
 *   RealCall의 originalRequest 필드를 직접 교체하는 것은 위험하므로
 *   executeOn()/execute() 내부의 Request 흐름을 따라 주입한다.
 *   실용적 대안: Feign/RestTemplate 계층에서 이미 traceparent를 주입하므로
 *   OkHttp 계층에서는 span 생성에 집중하고 traceparent 주입은 생략한다.
 *
 * P2 우선순위: Retrofit/Ktor 기반 HTTP 호출의 span 가시성 확보가 주목적.
 */
public final class OkHttpAdvice {

    // originalRequest 필드 캐싱
    public static volatile java.lang.reflect.Field ORIGINAL_REQUEST_FIELD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object call) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;

        String httpMethod = null;
        String urlStr = null;
        String urlPath = null;

        Object request = extractRequest(call);
        Object httpUrl = null;
        if (request != null) {
            httpMethod = extractMethod(request);
            httpUrl = extractUrl(request); // 1회만 호출 — 이후 재사용
            if (httpUrl != null) {
                urlStr = httpUrl.toString();
                urlPath = extractPath(httpUrl);
            }
        }

        String spanName;
        if (httpMethod != null && urlPath != null) {
            spanName = httpMethod + " " + urlPath;
        } else if (httpMethod != null) {
            spanName = "OkHttp " + httpMethod;
        } else {
            spanName = "OkHttp request";
        }

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.okhttp");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("http.framework", "okhttp");
        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (urlStr != null) span.setAttribute("url.full", UrlSanitizer.sanitize(urlStr));
        if (urlPath != null) span.setAttribute("url.path", urlPath);

        // host/port — 이미 추출한 httpUrl 재사용
        if (httpUrl != null) {
            try {
                String host = (String) httpUrl.getClass().getMethod("host").invoke(httpUrl);
                int port = (int) httpUrl.getClass().getMethod("port").invoke(httpUrl);
                if (host != null) {
                    span.setAttribute("server.address", host);
                    span.setAttribute("server.port", (long) port);
                    span.setAttribute("peer.service", host);
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

        AgentLogger.debug("[OkHttp] span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Return Object response,
            @Advice.Thrown Throwable error) {

        if (state == null) return;

        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
        } else if (response != null) {
            try {
                // okhttp3.Response.code()
                int statusCode = (int) response.getClass().getMethod("code").invoke(response);
                state.span.setAttribute("http.response.status_code", (long) statusCode);
                if (statusCode >= 400) {
                    state.span.setStatus(SpanStatus.ERROR, "HTTP " + statusCode);
                }
            } catch (Throwable ignored) {}
        }

        state.scope.close();
        state.span.end();

        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    /**
     * RealCall.originalRequest 필드에서 okhttp3.Request 추출.
     * OkHttp 3.x/4.x 모두 originalRequest 필드를 보유한다.
     */
    private static Object extractRequest(Object call) {
        try {
            java.lang.reflect.Field f = ORIGINAL_REQUEST_FIELD;
            if (f == null) {
                Class<?> clazz = call.getClass();
                while (clazz != null) {
                    try {
                        f = clazz.getDeclaredField("originalRequest");
                        f.setAccessible(true);
                        ORIGINAL_REQUEST_FIELD = f;
                        break;
                    } catch (NoSuchFieldException ignored) {
                        clazz = clazz.getSuperclass();
                    }
                }
            }
            return f != null ? f.get(call) : null;
        } catch (Throwable t) { return null; }
    }

    private static String extractMethod(Object request) {
        try { return (String) request.getClass().getMethod("method").invoke(request); }
        catch (Throwable t) { return null; }
    }

    private static Object extractUrl(Object request) {
        try { return request.getClass().getMethod("url").invoke(request); }
        catch (Throwable t) { return null; }
    }

    private static String extractPath(Object httpUrl) {
        try {
            // HttpUrl.encodedPath()
            return (String) httpUrl.getClass().getMethod("encodedPath").invoke(httpUrl);
        } catch (Throwable ignored) {}
        try {
            // fallback: uri().getPath()
            Object uri = httpUrl.getClass().getMethod("uri").invoke(httpUrl);
            return (String) uri.getClass().getMethod("getPath").invoke(uri);
        } catch (Throwable t) { return null; }
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

    private OkHttpAdvice() {}
}
