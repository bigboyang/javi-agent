package com.agent.instrumentation;

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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka @KafkaListener 계측.
 *
 * 대상: MessagingMessageListenerAdapter.onMessage(ConsumerRecord, ...)
 *
 * poll() exit 계측에서 변경한 이유:
 * - poll()은 레코드를 반환하기만 하고, 실제 @KafkaListener 처리(JDBC, Redis 등)는
 *   poll() 반환 이후에 일어난다.
 * - poll() exit에서 span을 시작하고 즉시 종료하면 downstream span이 consumer span의
 *   자식이 될 수 없다.
 * - MessagingMessageListenerAdapter.onMessage()는 실제 @KafkaListener 메서드 호출을
 *   감싸는 단일 진입점이므로 여기서 계측해야 올바른 parent-child 관계가 형성된다.
 *
 * 추가 속성:
 *   messaging.kafka.message.partition — 레코드가 consume된 파티션
 *   messaging.kafka.message.offset    — 레코드 오프셋
 *   messaging.message.id              — 레코드 키 (null이면 생략)
 */
public final class KafkaConsumerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) Object recordObj) {
        if (recordObj == null) return null;

        org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record =
                (org.apache.kafka.clients.consumer.ConsumerRecord<?, ?>) recordObj;

        String topic = record.topic();

        // traceparent 추출
        SpanContext parentContext = null;
        try {
            Map<String, String> carrier = new HashMap<>();
            for (org.apache.kafka.common.header.Header header : record.headers()) {
                if ("traceparent".equals(header.key())) {
                    carrier.put("traceparent",
                            new String(header.value(), StandardCharsets.UTF_8));
                }
            }
            if (!carrier.isEmpty()) {
                parentContext = TraceContextPropagator.extractStatic(
                        carrier, new MapTextMapGetter());
            }
        } catch (Throwable ignored) {}

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.kafka.consumer");
        com.agent.span.SpanBuilder builder = tracer.spanBuilder(topic + " process")
                .setSpanKind(SpanKind.CONSUMER);
        if (parentContext != null && parentContext.isValid()) {
            builder.setParent(parentContext);
        }

        Span span = builder.startSpan();
        span.setAttribute("messaging.system", "kafka");
        span.setAttribute("messaging.destination.name", topic);
        span.setAttribute("messaging.operation", "process");
        span.setAttribute("peer.service", "kafka");
        span.setAttribute("messaging.kafka.message.partition", (long) record.partition());
        span.setAttribute("messaging.kafka.message.offset", record.offset());

        Object key = record.key();
        if (key != null) span.setAttribute("messaging.message.id", key.toString());

        Scope scope = span.makeCurrent();

        // MDC 주입 — listener 실행 중 발생하는 로그에 traceId 포함
        String prevTraceId = MDC.get("traceId");
        String prevSpanId  = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId",  ctx.getSpanId());
        }

        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();

        // MDC 복원 — 컨슈머 스레드 풀의 스레드를 재사용하므로 반드시 원복해야 한다
        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId  == null) MDC.remove("spanId");
        else MDC.put("spanId",  state.prevSpanId);
    }

    public static final class State {
        public final Span   span;
        public final Scope  scope;
        public final String prevTraceId;
        public final String prevSpanId;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId) {
            this.span        = span;
            this.scope       = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId  = prevSpanId;
        }
    }

    private KafkaConsumerAdvice() {}
}
