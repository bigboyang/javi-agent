package com.agent.instrumentation;

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
 * Elasticsearch REST Client 비동기 계측.
 *
 * 대상: org.elasticsearch.client.RestClient.performRequestAsync(Request, ResponseListener)
 *
 * ResponseListener 인터페이스:
 *   - onSuccess(Response response)
 *   - onFailure(Exception exception)
 *
 * 전략: @Advice.Argument(value=1, readOnly=false)로 ResponseListener를 Proxy로 래핑.
 *        onSuccess/onFailure 호출 시 span 종료.
 *
 * 스레드 안전성:
 *   Scope는 submit 스레드(onExit)에서 닫는다.
 *   span.end()는 콜백 스레드(비동기)에서 호출 — 스레드 안전.
 */
public final class ElasticsearchAsyncAdvice {

    private static final String RESPONSE_LISTENER =
            "org.elasticsearch.client.ResponseListener";

    private static volatile java.lang.reflect.Method GET_METHOD    = null;
    private static volatile java.lang.reflect.Method GET_ENDPOINT  = null;
    private static volatile java.lang.reflect.Method GET_NODES     = null;
    private static volatile java.lang.reflect.Method GET_NODE_HOST = null;
    private static volatile java.lang.reflect.Method HOST_GET_NAME = null;
    private static volatile java.lang.reflect.Method HOST_GET_PORT = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.This Object restClient,
            @Advice.Argument(0) Object request,
            @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object listener) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;
        if (request == null) return null;

        String httpMethod = extractMethod(request);
        String endpoint = extractEndpoint(request);

        String spanName;
        if (httpMethod != null && endpoint != null) {
            String trimmed = endpoint.length() > 100 ? endpoint.substring(0, 100) : endpoint;
            spanName = "elasticsearch.async " + httpMethod + " " + trimmed;
        } else if (httpMethod != null) {
            spanName = "elasticsearch.async " + httpMethod;
        } else {
            spanName = "elasticsearch.async request";
        }

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.elasticsearch.async");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "elasticsearch");
        if (httpMethod != null) span.setAttribute("http.request.method", httpMethod);
        if (endpoint != null) span.setAttribute("url.path", endpoint);

        setNodeAttributes(span, restClient, endpoint);

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        // ResponseListener를 Proxy로 래핑 — @Argument(readOnly=false) 로컬변수 재할당 = write-back
        boolean delegated = false;
        if (listener != null) {
            Object wrapped = wrapListener(listener, span);
            if (wrapped != null) {
                listener = wrapped;
                delegated = true;
            }
        }

        AgentLogger.debug("[ES Async] span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId, delegated);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Thrown Throwable error) {

        if (state == null) return;

        state.scope.close();
        restoreMdc(state);

        if (!state.spanDelegated) {
            if (error != null) {
                state.span.recordException(error);
                state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            }
            state.span.end();
        }
    }

    static Object wrapListener(Object listener, Span span) {
        try {
            Class<?>[] ifaces = findInterfaces(listener, RESPONSE_LISTENER);
            if (ifaces == null) return null;

            ClassLoader cl = listener.getClass().getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();

            final Span capturedSpan = span;
            final Object capturedListener = listener;

            return java.lang.reflect.Proxy.newProxyInstance(cl, ifaces,
                    (proxy, method, methodArgs) -> {
                        String name = method.getName();
                        if ("onSuccess".equals(name)) {
                            try {
                                Object response = (methodArgs != null && methodArgs.length > 0)
                                        ? methodArgs[0] : null;
                                if (response != null) extractAndSetStatusCode(response, capturedSpan);
                            } finally {
                                capturedSpan.end();
                            }
                        } else if ("onFailure".equals(name)) {
                            try {
                                if (methodArgs != null && methodArgs.length > 0
                                        && methodArgs[0] instanceof Exception) {
                                    Exception ex = (Exception) methodArgs[0];
                                    capturedSpan.recordException(ex);
                                    capturedSpan.setStatus(SpanStatus.ERROR, ex.getMessage());
                                }
                            } finally {
                                capturedSpan.end();
                            }
                        }
                        try {
                            return method.invoke(capturedListener, methodArgs);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            throw (cause != null ? cause : e);
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private static void extractAndSetStatusCode(Object response, Span span) {
        try {
            Object statusLine = response.getClass().getMethod("getStatusLine").invoke(response);
            if (statusLine != null) {
                int code = (int) statusLine.getClass().getMethod("getStatusCode").invoke(statusLine);
                span.setAttribute("http.response.status_code", (long) code);
                if (code >= 400) span.setStatus(SpanStatus.ERROR, "HTTP " + code);
            }
        } catch (Throwable ignored) {}
    }

    static Class<?>[] findInterfaces(Object obj, String targetInterfaceName) {
        if (obj == null) return null;
        ClassLoader cl = obj.getClass().getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        try {
            Class<?> iface = Class.forName(targetInterfaceName, false, cl);
            if (iface.isInstance(obj)) {
                return new Class<?>[]{iface};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractMethod(Object request) {
        try {
            java.lang.reflect.Method m = GET_METHOD;
            if (m == null) { m = request.getClass().getMethod("getMethod"); GET_METHOD = m; }
            return (String) m.invoke(request);
        } catch (Throwable t) { return null; }
    }

    private static String extractEndpoint(Object request) {
        try {
            java.lang.reflect.Method m = GET_ENDPOINT;
            if (m == null) { m = request.getClass().getMethod("getEndpoint"); GET_ENDPOINT = m; }
            return (String) m.invoke(request);
        } catch (Throwable t) { return null; }
    }

    private static void setNodeAttributes(Span span, Object restClient, String endpoint) {
        if (restClient == null) return;
        try {
            java.lang.reflect.Method getNodes = GET_NODES;
            if (getNodes == null) {
                getNodes = restClient.getClass().getMethod("getNodes");
                GET_NODES = getNodes;
            }
            java.util.List<?> nodes = (java.util.List<?>) getNodes.invoke(restClient);
            if (nodes == null || nodes.isEmpty()) return;
            Object node = nodes.get(0);

            java.lang.reflect.Method getHost = GET_NODE_HOST;
            if (getHost == null) {
                getHost = node.getClass().getMethod("getHost");
                GET_NODE_HOST = getHost;
            }
            Object host = getHost.invoke(node);
            if (host == null) return;

            java.lang.reflect.Method getHostName = HOST_GET_NAME;
            if (getHostName == null) {
                getHostName = host.getClass().getMethod("getHostName");
                HOST_GET_NAME = getHostName;
            }
            java.lang.reflect.Method getPort = HOST_GET_PORT;
            if (getPort == null) {
                getPort = host.getClass().getMethod("getPort");
                HOST_GET_PORT = getPort;
            }

            String hostname = (String) getHostName.invoke(host);
            int port = (int) getPort.invoke(host);

            if (hostname != null) span.setAttribute("server.address", hostname);
            if (port > 0)         span.setAttribute("server.port", (long) port);
            if (hostname != null && endpoint != null) {
                span.setAttribute("url.full", host.toString() + endpoint);
            }
        } catch (Throwable t) {
            AgentLogger.debug("[ES Async] setNodeAttributes failed: " + t.getMessage());
        }
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
        public final boolean spanDelegated;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId, boolean spanDelegated) {
            this.span = span;
            this.scope = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId = prevSpanId;
            this.spanDelegated = spanDelegated;
        }
    }

    private ElasticsearchAsyncAdvice() {}
}
