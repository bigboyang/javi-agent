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
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * gRPC 클라이언트 호출 계측.
 *
 * 대상: io.grpc.internal.ClientCallImpl.start(ClientCall.Listener, Metadata)
 *
 * 선택 근거:
 * - start()는 모든 gRPC 호출(blocking, async, streaming)이 공통으로 지나는 단일 진입점이다.
 * - blockingUnaryCall은 내부적으로 ClientCallImpl.start()를 호출하므로 별도 계측 불필요.
 * - AbstractStub.build()는 채널/옵션 설정 단계이므로 적합하지 않다.
 * - ClientInterceptor 기반 계측은 앱 코드 변경이 필요하므로 agent 계측 목적에 맞지 않다.
 *
 * MethodDescriptor 추출:
 * - ClientCallImpl은 생성 시 MethodDescriptor를 필드로 보유한다.
 * - reflection으로 fullMethodName을 읽어 "ServiceName/MethodName" 형식으로 파싱한다.
 *
 * W3C traceparent 주입:
 * - Metadata 객체에 gRPC 메타데이터 키를 통해 traceparent를 주입한다.
 * - io.grpc.Metadata.Key.of()로 ASCII 키를 생성, put()으로 값 설정.
 *
 * Span 명: "grpc ServiceName/MethodName" (예: "grpc UserService/GetUser")
 *
 * 성능:
 * - MethodDescriptor reflection 결과는 캐싱하지 않는다 (서비스별로 다른 descriptor 인스턴스).
 * - Metadata Key 생성은 static 캐싱 불가 (gRPC는 Key 타입 파라미터 때문에 인스턴스별 생성 필요).
 * - 대신 fullMethodName 파싱은 String.lastIndexOf 한 번으로 처리해 오버헤드 최소화.
 */
public final class GrpcClientAdvice {

    // MethodDescriptor.getFullMethodName() 메서드 — 클래스 로드 이후 캐싱
    public static volatile Method GET_FULL_METHOD_NAME = null;
    // ClientCallImpl.method 필드 — 첫 호출 시 캐싱
    public static volatile java.lang.reflect.Field METHOD_DESCRIPTOR_FIELD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.This Object clientCall,
            @Advice.Argument(0) Object responseListener,
            @Advice.Argument(1) Object metadata) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;

        // MethodDescriptor에서 서비스/메서드명 추출
        String fullMethodName = extractFullMethodName(clientCall);
        String serviceName = "unknown";
        String methodName = "unknown";

        if (fullMethodName != null) {
            // gRPC fullMethodName 형식: "package.ServiceName/MethodName"
            int slash = fullMethodName.lastIndexOf('/');
            if (slash > 0) {
                String svc = fullMethodName.substring(0, slash);
                int dot = svc.lastIndexOf('.');
                serviceName = dot >= 0 ? svc.substring(dot + 1) : svc;
                methodName = fullMethodName.substring(slash + 1);
            }
        }

        String spanName = "grpc " + serviceName + "/" + methodName;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.grpc.client");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        // OTel RPC 시맨틱 속성 — https://opentelemetry.io/docs/specs/semconv/rpc/
        span.setAttribute("rpc.system", "grpc");
        span.setAttribute("rpc.service", serviceName);
        span.setAttribute("rpc.method", methodName);
        if (fullMethodName != null) {
            span.setAttribute("rpc.grpc.full_method", fullMethodName);
        }

        // W3C traceparent를 gRPC Metadata에 주입 — 서버 측에서 분산 트레이싱 컨텍스트 복원
        injectTraceparent(metadata, span);

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        AgentLogger.debug("[gRPC-Client] span started: " + spanName);
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

    /**
     * ClientCallImpl 인스턴스에서 MethodDescriptor의 fullMethodName을 추출한다.
     *
     * ClientCallImpl 내부 구조 (gRPC 1.x 공통):
     *   private final MethodDescriptor<ReqT, RespT> method;
     *
     * MethodDescriptor.getFullMethodName() -> "package.Service/Method"
     */
    public static String extractFullMethodName(Object clientCall) {
        try {
            // 첫 호출 시 field 탐색 및 캐싱
            java.lang.reflect.Field field = METHOD_DESCRIPTOR_FIELD;
            if (field == null) {
                // ClientCallImpl 또는 그 상위 클래스에서 'method' 필드 탐색
                Class<?> clazz = clientCall.getClass();
                while (clazz != null) {
                    try {
                        field = clazz.getDeclaredField("method");
                        field.setAccessible(true);
                        METHOD_DESCRIPTOR_FIELD = field;
                        break;
                    } catch (NoSuchFieldException e) {
                        clazz = clazz.getSuperclass();
                    }
                }
            }
            if (field == null) return null;

            Object descriptor = field.get(clientCall);
            if (descriptor == null) return null;

            // MethodDescriptor.getFullMethodName()
            Method getFullName = GET_FULL_METHOD_NAME;
            if (getFullName == null) {
                getFullName = descriptor.getClass().getMethod("getFullMethodName");
                GET_FULL_METHOD_NAME = getFullName;
            }
            return (String) getFullName.invoke(descriptor);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * gRPC Metadata에 W3C traceparent 헤더를 주입한다.
     *
     * Metadata API:
     *   Metadata.Key<String> key = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)
     *   metadata.put(key, value)
     *
     * 매 호출마다 Key를 생성하지 않기 위해 static 필드에 캐싱을 시도하나,
     * Metadata.Key는 제네릭 타입 정보를 런타임에 보유하므로 reflection으로 안전하게 생성한다.
     */
    public static volatile Object TRACEPARENT_KEY = null;
    public static volatile Method METADATA_PUT_METHOD = null;

    public static void injectTraceparent(Object metadata, Span span) {
        if (metadata == null) return;
        SpanContext ctx = span.getContext();
        if (ctx == null || !ctx.isValid()) return;

        try {
            // traceparent 값 생성: "00-traceId-spanId-01"
            String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-01";

            // Metadata.Key 캐싱
            Object key = TRACEPARENT_KEY;
            if (key == null) {
                // Metadata.ASCII_STRING_MARSHALLER 가져오기
                Class<?> metadataClass = metadata.getClass();
                // io.grpc.Metadata.Key.of(name, marshaller) static 메서드 호출
                // Key는 inner class이므로 forName으로 접근
                Class<?> keyClass = Class.forName("io.grpc.Metadata$Key",
                        true, metadata.getClass().getClassLoader());
                // ASCII_STRING_MARSHALLER 필드
                java.lang.reflect.Field marshallerField = metadataClass
                        .getField("ASCII_STRING_MARSHALLER");
                Object marshaller = marshallerField.get(null);
                Method ofMethod = keyClass.getMethod("of", String.class, Class.forName(
                        "io.grpc.Metadata$AsciiMarshaller",
                        true, metadata.getClass().getClassLoader()));
                key = ofMethod.invoke(null, "traceparent", marshaller);
                TRACEPARENT_KEY = key;
            }

            // metadata.put(key, value)
            Method putMethod = METADATA_PUT_METHOD;
            if (putMethod == null) {
                putMethod = metadata.getClass().getMethod("put",
                        Class.forName("io.grpc.Metadata$Key",
                                true, metadata.getClass().getClassLoader()),
                        Object.class);
                METADATA_PUT_METHOD = putMethod;
            }
            putMethod.invoke(metadata, key, traceparent);

        } catch (Throwable t) {
            // 주입 실패 — span은 계속 진행 (분산 추적 단절만 발생)
            AgentLogger.debug("[gRPC-Client] traceparent inject 실패: " + t.getMessage());
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

    private GrpcClientAdvice() {}
}
