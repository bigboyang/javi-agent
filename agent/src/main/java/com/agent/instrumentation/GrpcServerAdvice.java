package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.propagation.MapTextMapGetter;
import com.agent.propagation.TraceContextPropagator;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * gRPC 서버 수신 호출 계측.
 *
 * <h3>계측 대상</h3>
 * {@code io.grpc.stub.ServerCalls} 내부 ServerCall.Listener 구현체의
 * {@code onHalfClose()} — 클라이언트 요청이 완전히 수신된 시점.
 * <ul>
 *   <li>UnaryServerCallHandler$UnaryServerCallListener</li>
 *   <li>ClientStreamingServerCallHandler$StreamingServerCallListener</li>
 * </ul>
 *
 * <h3>W3C traceparent 추출</h3>
 * {@code listener.headers (io.grpc.Metadata)} 필드에서 {@code traceparent} 헤더 값을 읽는다.
 * gRPC 클라이언트(GrpcClientAdvice)가 주입한 값을 복원한다.
 *
 * <h3>메서드명 추출 경로</h3>
 * {@code listener.responseObserver → call → getMethodDescriptor() → getFullMethodName()}
 *
 * <h3>제한</h3>
 * 비동기 스트리밍 RPC는 onHalfClose 실행 시간만 캡처한다 (응답 전송 이후 제외).
 * 동기 Unary RPC에서는 완전한 end-to-end 시간을 캡처한다.
 */
public final class GrpcServerAdvice {

    // ---- Reflection 캐시 (volatile — 단순 조회라 double-checked lock 불필요) ----
    public static volatile Method METADATA_KEY_OF_METHOD  = null;
    public static volatile Method METADATA_GET_METHOD     = null;
    public static volatile Object ASCII_STRING_MARSHALLER = null;

    // Field / Method reflection 캐시 — getDeclaredField 반복 호출 방지 (GrpcClientAdvice 패턴)
    public static volatile Field  LISTENER_HEADERS_FIELD            = null;
    public static volatile Field  LISTENER_RESPONSE_OBSERVER_FIELD  = null;
    public static volatile Field  RESPONSE_OBSERVER_CALL_FIELD      = null;
    public static volatile Method GET_METHOD_DESCRIPTOR_METHOD      = null;
    public static volatile Method GET_FULL_METHOD_NAME_METHOD       = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object listener) {

        // 1. gRPC Metadata에서 traceparent / tracestate 추출
        SpanContext remoteParent = null;
        Field hf = LISTENER_HEADERS_FIELD;
        if (hf == null) { hf = findField(listener.getClass(), "headers"); LISTENER_HEADERS_FIELD = hf; }
        Object headers = null;
        try { if (hf != null) headers = hf.get(listener); } catch (Throwable ignored) {}
        if (headers != null) {
            String traceparent = readMetadataHeader(headers, "traceparent");
            if (traceparent != null) {
                Map<String, String> hMap = new HashMap<>(4);
                hMap.put("traceparent", traceparent);
                String tracestate = readMetadataHeader(headers, "tracestate");
                if (tracestate != null) hMap.put("tracestate", tracestate);
                SpanContext extracted = TraceContextPropagator.extractStatic(
                        hMap, new MapTextMapGetter());
                if (extracted != null && extracted.isValid()) remoteParent = extracted;
            }
        }

        // 2. MethodDescriptor에서 서비스/메서드명 추출
        String fullMethodName = extractFullMethodName(listener);
        String serviceName = "unknown";
        String methodName  = "unknown";
        if (fullMethodName != null) {
            int slash = fullMethodName.lastIndexOf('/');
            if (slash > 0) {
                String svc = fullMethodName.substring(0, slash);
                int dot = svc.lastIndexOf('.');
                serviceName = dot >= 0 ? svc.substring(dot + 1) : svc;
                methodName  = fullMethodName.substring(slash + 1);
            }
        }

        // 3. SERVER span 생성
        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.grpc.server");
        com.agent.span.SpanBuilder builder = tracer
                .spanBuilder("grpc " + serviceName + "/" + methodName)
                .setSpanKind(SpanKind.SERVER);
        if (remoteParent != null) builder.setParent(remoteParent);

        Span span = builder.startSpan();

        // OTel RPC 시맨틱 속성 — https://opentelemetry.io/docs/specs/semconv/rpc/grpc/
        span.setAttribute("rpc.system", "grpc");
        span.setAttribute("rpc.service", serviceName);
        span.setAttribute("rpc.method", methodName);
        if (fullMethodName != null) span.setAttribute("com.agent.grpc.full_method", fullMethodName);

        Scope scope = span.makeCurrent();

        // MDC 주입 — 로그에 traceId/spanId 자동 포함
        String prevTraceId = MDC.get("traceId");
        String prevSpanId  = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId",  ctx.getSpanId());
        }

        AgentLogger.debug("[gRPC-Server] span started: grpc " + serviceName + "/" + methodName);
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

    // ---- Metadata 읽기 (reflection) ----------------------------------------

    /**
     * gRPC Metadata 객체에서 ASCII 헤더 값을 반환한다.
     *
     * <pre>
     * Metadata.Key&lt;String&gt; key = Metadata.Key.of(name, ASCII_STRING_MARSHALLER);
     * String value = metadata.get(key);
     * </pre>
     *
     * Metadata.Key 생성 메서드와 ASCII_STRING_MARSHALLER 를 캐싱해 오버헤드를 최소화한다.
     */
    public static String readMetadataHeader(Object metadata, String headerName) {
        try {
            ClassLoader cl = metadata.getClass().getClassLoader();

            // ASCII_STRING_MARSHALLER 캐싱
            Object marshaller = ASCII_STRING_MARSHALLER;
            if (marshaller == null) {
                marshaller = metadata.getClass().getField("ASCII_STRING_MARSHALLER").get(null);
                ASCII_STRING_MARSHALLER = marshaller;
            }

            // Metadata.Key.of(name, marshaller) 메서드 캐싱
            Method keyOf = METADATA_KEY_OF_METHOD;
            if (keyOf == null) {
                Class<?> keyClass    = Class.forName("io.grpc.Metadata$Key", true, cl);
                Class<?> marshClass  = Class.forName("io.grpc.Metadata$AsciiMarshaller", true, cl);
                keyOf = keyClass.getMethod("of", String.class, marshClass);
                METADATA_KEY_OF_METHOD = keyOf;
            }

            // metadata.get(key) 메서드 캐싱
            Method get = METADATA_GET_METHOD;
            if (get == null) {
                Class<?> keyClass = Class.forName("io.grpc.Metadata$Key", true, cl);
                get = metadata.getClass().getMethod("get", keyClass);
                METADATA_GET_METHOD = get;
            }

            Object metaKey = keyOf.invoke(null, headerName, marshaller);
            return (String) get.invoke(metadata, metaKey);

        } catch (Throwable t) {
            return null;
        }
    }

    // ---- MethodDescriptor 추출 (reflection) ------------------------------------

    /**
     * listener → responseObserver → call → MethodDescriptor → getFullMethodName() 경로로
     * "package.ServiceName/MethodName" 형식의 전체 메서드명을 반환한다.
     * Field / Method 결과는 static 캐시에 저장해 getDeclaredField 반복 호출을 방지한다.
     */
    private static String extractFullMethodName(Object listener) {
        try {
            // listener.responseObserver 필드 캐싱
            Field roField = LISTENER_RESPONSE_OBSERVER_FIELD;
            if (roField == null) {
                roField = findField(listener.getClass(), "responseObserver");
                LISTENER_RESPONSE_OBSERVER_FIELD = roField;
            }
            if (roField == null) return null;
            Object responseObserver = roField.get(listener);
            if (responseObserver == null) return null;

            // responseObserver.call 필드 캐싱
            Field callField = RESPONSE_OBSERVER_CALL_FIELD;
            if (callField == null) {
                callField = findField(responseObserver.getClass(), "call");
                RESPONSE_OBSERVER_CALL_FIELD = callField;
            }
            if (callField == null) return null;
            Object call = callField.get(responseObserver);
            if (call == null) return null;

            // ServerCall.getMethodDescriptor() 메서드 캐싱
            Method getDesc = GET_METHOD_DESCRIPTOR_METHOD;
            if (getDesc == null) {
                getDesc = call.getClass().getMethod("getMethodDescriptor");
                GET_METHOD_DESCRIPTOR_METHOD = getDesc;
            }
            Object descriptor = getDesc.invoke(call);
            if (descriptor == null) return null;

            // MethodDescriptor.getFullMethodName() 메서드 캐싱
            Method getFullName = GET_FULL_METHOD_NAME_METHOD;
            if (getFullName == null) {
                getFullName = descriptor.getClass().getMethod("getFullMethodName");
                GET_FULL_METHOD_NAME_METHOD = getFullName;
            }
            return (String) getFullName.invoke(descriptor);

        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 클래스 계층을 순회해 필드를 탐색하고 accessible 처리 후 반환한다.
     * 값을 읽지 않으므로 호출 측이 캐시에 저장해 재사용할 수 있다.
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ---- State ---------------------------------------------------------------

    public static final class State {
        public final Span    span;
        public final Scope   scope;
        public final String  prevTraceId;
        public final String  prevSpanId;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId) {
            this.span        = span;
            this.scope       = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId  = prevSpanId;
        }
    }

    private GrpcServerAdvice() {}
}
