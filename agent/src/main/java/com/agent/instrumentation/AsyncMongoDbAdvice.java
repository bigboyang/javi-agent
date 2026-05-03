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
 * MongoDB 비동기(Reactive) 드라이버 계측.
 *
 * 대상: com.mongodb.internal.connection.InternalStreamConnection.sendAndReceiveAsync(
 *         CommandMessage, Decoder, SessionContext, [OperationContext,] SingleResultCallback)
 *
 * - MongoDB 3.x: 4개 인자 — 마지막(index 3)이 SingleResultCallback
 * - MongoDB 4.x: 5개 인자 — 마지막(index 4)이 SingleResultCallback
 *
 * 전략:
 *   @AllArguments(readOnly=false)로 마지막 인자(콜백)를 java.lang.reflect.Proxy로 래핑.
 *   Proxy의 onResult() 호출 시 span.end() 실행 (비동기 스레드).
 *
 * 스레드 안전성:
 *   Scope는 submit 스레드(onExit)에서 반드시 닫는다 (ThreadLocal).
 *   span.end()는 스레드 안전하므로 Proxy 콜백 스레드에서 호출 가능.
 */
public final class AsyncMongoDbAdvice {

    private static final String SINGLE_RESULT_CALLBACK =
            "com.mongodb.internal.async.SingleResultCallback";

    // namespace 추출 메서드 캐싱 (DCL 패턴)
    private static volatile java.lang.reflect.Method GET_NAMESPACE_METHOD = null;
    private static volatile java.lang.reflect.Method GET_DB_NAME_METHOD = null;
    // SingleResultCallback 클래스 캐싱 (Class.forName 반복 방지)
    private static volatile Class<?> CALLBACK_IFACE_CLASS = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) Object commandMessage,
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;
        if (commandMessage == null || args == null || args.length < 4) return null;

        String dbOperation = "command";
        String collectionName = "unknown";

        try {
            Object bsonDoc = MongoDbAdvice.extractCommandDocument(commandMessage);
            if (bsonDoc != null) {
                String cmdKey = MongoDbAdvice.extractFirstKey(bsonDoc);
                if (cmdKey != null) {
                    dbOperation = cmdKey.toLowerCase();
                    String col = MongoDbAdvice.extractStringValue(bsonDoc, cmdKey);
                    if (col != null && !col.isEmpty()) collectionName = col;
                }
            }
        } catch (Throwable ignored) {}

        String spanName = "mongodb.async " + collectionName + "." + dbOperation;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.mongodb.reactive");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "mongodb");
        span.setAttribute("db.operation.name", dbOperation);
        span.setAttribute("db.collection.name", collectionName);
        span.setAttribute("db.transport", "async");
        span.setAttribute("peer.service", "mongodb");

        try {
            java.lang.reflect.Method getNsMethod = GET_NAMESPACE_METHOD;
            if (getNsMethod == null) {
                getNsMethod = commandMessage.getClass().getMethod("getNamespace");
                GET_NAMESPACE_METHOD = getNsMethod;
            }
            Object namespace = getNsMethod.invoke(commandMessage);
            if (namespace != null) {
                java.lang.reflect.Method getDbNameMethod = GET_DB_NAME_METHOD;
                if (getDbNameMethod == null) {
                    getDbNameMethod = namespace.getClass().getMethod("getDatabaseName");
                    GET_DB_NAME_METHOD = getDbNameMethod;
                }
                String dbName = (String) getDbNameMethod.invoke(namespace);
                if (dbName != null) span.setAttribute("db.namespace", dbName);
            }
        } catch (Throwable ignored) {}

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        // 마지막 인자(SingleResultCallback)를 Proxy로 래핑
        boolean delegated = false;
        Object original = args[args.length - 1];
        if (original != null) {
            Object wrapped = wrapCallback(original, span);
            if (wrapped != null) {
                args[args.length - 1] = wrapped;
                delegated = true;
            }
        }

        AgentLogger.debug("[MongoDB Async] span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId, delegated);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Thrown Throwable error) {

        if (state == null) return;

        // Scope는 반드시 submit 스레드(현재 스레드)에서 닫는다
        state.scope.close();
        restoreMdc(state);

        if (!state.spanDelegated) {
            // 래핑 실패 또는 메서드 자체가 예외를 던진 경우 동기로 종료
            if (error != null) {
                state.span.recordException(error);
                state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            }
            state.span.end();
        }
        // spanDelegated=true: Proxy의 onResult에서 span.end() 호출
    }

    static Object wrapCallback(Object original, Span span) {
        try {
            Class<?>[] ifaces = findCallbackInterfaces(original, SINGLE_RESULT_CALLBACK);
            if (ifaces == null) return null;

            ClassLoader cl = original.getClass().getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();

            final Span capturedSpan = span;
            final Object capturedOriginal = original;

            return java.lang.reflect.Proxy.newProxyInstance(cl, ifaces,
                    (proxy, method, methodArgs) -> {
                        if ("onResult".equals(method.getName())) {
                            Throwable err = (methodArgs != null && methodArgs.length > 1)
                                    ? (Throwable) methodArgs[1] : null;
                            try {
                                if (err != null) {
                                    capturedSpan.recordException(err);
                                    capturedSpan.setStatus(SpanStatus.ERROR, err.getMessage());
                                }
                            } finally {
                                capturedSpan.end();
                            }
                        }
                        try {
                            return method.invoke(capturedOriginal, methodArgs);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            throw (cause != null ? cause : e);
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?>[] findCallbackInterfaces(Object callback, String targetInterfaceName) {
        if (callback == null) return null;
        // 캐시된 클래스 먼저 확인
        Class<?> cached = CALLBACK_IFACE_CLASS;
        if (cached != null && cached.isInstance(callback)) {
            return new Class<?>[]{cached};
        }
        ClassLoader cl = callback.getClass().getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        try {
            Class<?> iface = Class.forName(targetInterfaceName, false, cl);
            if (iface.isInstance(callback)) {
                CALLBACK_IFACE_CLASS = iface;
                return new Class<?>[]{iface};
            }
        } catch (Throwable ignored) {}
        return null;
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

    private AsyncMongoDbAdvice() {}
}
