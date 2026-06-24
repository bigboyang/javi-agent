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
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.slf4j.MDC;

/**
 * Feign HTTP 클라이언트 계측.
 *
 * 대상: feign.Client 인터페이스의 모든 구현체.
 *        feign.Client.execute(Request request, Options options)
 *
 * 전략:
 *   - feign.Request에서 method, URL을 추출하여 CLIENT span 생성
 *   - feign.Request를 재구성하여 traceparent 헤더 주입 (@Argument readOnly=false)
 *   - feign.Response에서 HTTP 응답 코드를 추출하여 span에 기록
 *
 * traceparent 주입:
 *   Feign Request는 불변이므로 Request.create()로 헤더를 추가한 새 Request를 생성.
 *   Feign 버전별 create() 시그니처가 다르므로 다중 폴백으로 처리한다.
 *
 * 참고:
 *   feign.Client.Default (HttpURLConnection 기반)는 별도 HTTP 클라이언트 advice의
 *   대상이 아니므로 이 Advice에서 직접 traceparent를 주입하는 것이 필수적이다.
 */
public final class FeignClientAdvice {

    // hex 룩업 테이블 (String.format 대체)
    private static final String[] HEX_BYTES;
    static {
        HEX_BYTES = new String[256];
        for (int i = 0; i < 256; i++) {
            HEX_BYTES[i] = String.format("%02x", i);
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request,
            @Advice.Argument(1) Object options) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;
        if (request == null) return null;

        String httpMethod = extractMethod(request);
        String url = extractUrl(request);

        // URI를 1회만 파싱하여 path/host 모두 추출
        String path = null;
        String host = null;
        if (url != null) {
            String[] uriComponents = parseUri(url);
            path = uriComponents[0];
            host = uriComponents[1];
        }

        String spanName;
        if (httpMethod != null && path != null) {
            spanName = httpMethod + " " + path;
        } else if (httpMethod != null) {
            spanName = "Feign " + httpMethod;
        } else {
            spanName = "Feign request";
        }

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.feign");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("http.framework", "feign");
        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (url != null) span.setAttribute("url.full", UrlSanitizer.sanitize(url));
        if (path != null) span.setAttribute("url.path", path);
        if (host != null) span.setAttribute("peer.service", host);

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        // traceparent 헤더 주입: Request 재구성
        if (ctx != null && ctx.isValid()) {
            String traceparent = buildTraceparent(ctx);
            Object mutated = injectTraceparent(request, traceparent);
            if (mutated != null) {
                request = mutated; // @Argument(readOnly=false) write-back
            }
        }

        AgentLogger.debug("[Feign] span started: " + spanName);
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
                // feign.Response.status()
                int statusCode = (int) response.getClass().getMethod("status").invoke(response);
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
     * Feign Request에 traceparent 헤더를 추가하여 새 Request를 생성한다.
     *
     * Feign 버전별 Request.create() 시그니처:
     *   - 9.x : create(String method, String url, Map headers, byte[] body, Charset charset)
     *   - 10.x: create(HttpMethod method, String url, Map headers, Body body, RequestTemplate rt)
     *   - 11.x: 동일 또는 유사
     *
     * 공통 접근: headers Map에 traceparent를 추가한 뒤 기존 Request와 같은 인자로 create() 호출.
     */
    private static Object injectTraceparent(Object request, String traceparent) {
        try {
            // 기존 헤더 복사 (Map<String, Collection<String>>)
            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.Collection<String>> origHeaders =
                    (java.util.Map<String, java.util.Collection<String>>)
                            request.getClass().getMethod("headers").invoke(request);

            java.util.Map<String, java.util.Collection<String>> newHeaders = new java.util.HashMap<>();
            if (origHeaders != null) newHeaders.putAll(origHeaders);
            newHeaders.put("traceparent",
                    java.util.Collections.singletonList(traceparent));

            // Request.create() — 리플렉션으로 올바른 시그니처 탐색
            Object method = request.getClass().getMethod("method").invoke(request);
            String url = (String) request.getClass().getMethod("url").invoke(request);
            Object body = tryGetBody(request);
            Object requestTemplate = tryGetRequestTemplate(request);

            Class<?> requestClass = request.getClass();

            // Feign 10.x/11.x: create(HttpMethod, String, Map, Body, RequestTemplate)
            if (requestTemplate != null && body != null) {
                try {
                    Object newRequest = requestClass.getMethod("create",
                            method.getClass(), String.class, java.util.Map.class,
                            body.getClass().getInterfaces().length > 0
                                    ? body.getClass().getInterfaces()[0] : body.getClass(),
                            requestTemplate.getClass())
                            .invoke(null, method, url, newHeaders, body, requestTemplate);
                    if (newRequest != null) return newRequest;
                } catch (Throwable ignored) {}
            }

            // Feign 9.x: create(String, String, Map, byte[], Charset)
            try {
                byte[] bodyBytes = (byte[]) request.getClass().getMethod("body").invoke(request);
                Object charset = null;
                try { charset = request.getClass().getMethod("charset").invoke(request); }
                catch (Throwable ignored) {}
                return requestClass.getMethod("create",
                        String.class, String.class, java.util.Map.class,
                        byte[].class, java.nio.charset.Charset.class)
                        .invoke(null, method.toString(), url, newHeaders, bodyBytes, charset);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
        return null;
    }

    private static Object tryGetBody(Object request) {
        try { return request.getClass().getMethod("body").invoke(request); }
        catch (Throwable ignored) { return null; }
    }

    private static Object tryGetRequestTemplate(Object request) {
        try { return request.getClass().getMethod("requestTemplate").invoke(request); }
        catch (Throwable ignored) { return null; }
    }

    private static String extractMethod(Object request) {
        try {
            Object m = request.getClass().getMethod("method").invoke(request);
            return m != null ? m.toString() : null;
        } catch (Throwable t) { return null; }
    }

    private static String extractUrl(Object request) {
        try {
            return (String) request.getClass().getMethod("url").invoke(request);
        } catch (Throwable t) { return null; }
    }

    /** URI를 1회 파싱하여 [path, host]를 반환 (null-safe). */
    private static String[] parseUri(String url) {
        String path = null;
        String host = null;
        try {
            java.net.URI uri = new java.net.URI(url);
            String p = uri.getPath();
            path = (p == null || p.isEmpty()) ? "/" : p;
            host = uri.getHost();
        } catch (Throwable t) {
            int dSlash = url.indexOf("//");
            if (dSlash >= 0) {
                int slash = url.indexOf('/', dSlash + 2);
                path = slash >= 0 ? url.substring(slash) : "/";
            }
        }
        return new String[]{path, host};
    }

    private static String buildTraceparent(SpanContext ctx) {
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId()
                + "-" + HEX_BYTES[ctx.getTraceFlags().asByte() & 0xFF];
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

    private FeignClientAdvice() {}
}
