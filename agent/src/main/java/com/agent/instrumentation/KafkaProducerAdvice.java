package com.agent.instrumentation;

import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.nio.charset.StandardCharsets;

/**
 * Kafka Producer 계측.
 *
 * Bug fix: traceparent sampled flag — span.isRecording() 기반으로 "01"/"00" 동적 결정.
 *   (이전: 항상 "01" 하드코딩 → 비샘플 span에서도 sampled로 잘못 전파)
 *
 * 추가 속성:
 *   messaging.kafka.destination.partition — pr.partition() (null이면 브로커 자동 할당)
 *   messaging.message_id                  — 레코드 키 (null이면 생략)
 */
public final class KafkaProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) Object record) {
        if (record == null) return null;

        org.apache.kafka.clients.producer.ProducerRecord<?, ?> pr =
                (org.apache.kafka.clients.producer.ProducerRecord<?, ?>) record;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.kafka.producer");
        String topic = pr.topic();

        Span span = tracer.spanBuilder(topic + " send")
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();

        span.setAttribute("messaging.system", "kafka");
        span.setAttribute("messaging.destination.name", topic);
        span.setAttribute("messaging.operation", "send");
        span.setAttribute("peer.service", "kafka");

        // partition (null = 브로커 자동 배정, 알 수 없으면 속성 생략)
        Integer partition = pr.partition();
        if (partition != null) {
            span.setAttribute("messaging.kafka.destination.partition", (long) partition);
        }

        // message.id: 레코드 키
        Object key = pr.key();
        if (key != null) {
            span.setAttribute("messaging.message.id", key.toString());
        }

        // W3C traceparent 주입 — sampled flag는 span.isRecording() 기반으로 결정
        try {
            SpanContext ctx = span.getContext();
            String sampledFlag = span.isRecording() ? "01" : "00";
            String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + sampledFlag;
            pr.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}

        return new State(span, span.makeCurrent());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(com.agent.span.SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();
    }

    public static final class State {
        public final Span span;
        public final com.agent.span.Scope scope;

        public State(Span span, com.agent.span.Scope scope) {
            this.span  = span;
            this.scope = scope;
        }
    }

    private KafkaProducerAdvice() {}
}
