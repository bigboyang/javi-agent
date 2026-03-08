package com.agent.instrumentation;

import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.nio.charset.StandardCharsets;

/**
 * Kafka Producer 계측.
 * 메시지 전송 시 스팬을 생성하고 traceparent를 헤더에 주입합니다.
 */
public final class KafkaProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) Object record) {
        if (record == null) return null;

        // Reflection 없이 타입 캐스팅 시도 (Advice는 대상 클래스 로더에서 실행되므로 가능)
        // 단, 메서드 서명에는 Object를 사용하여 에이전트 클래스 로더에서의 의존성 문제를 피함
        org.apache.kafka.clients.producer.ProducerRecord<?, ?> pr = (org.apache.kafka.clients.producer.ProducerRecord<?, ?>) record;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.kafka.producer");
        String topic = pr.topic();
        String spanName = topic + " send";

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();

        span.setAttribute("messaging.system", "kafka");
        span.setAttribute("messaging.destination", topic);
        span.setAttribute("messaging.operation", "send");

        // 컨텍스트 전파 (Injection)
        try {
            org.apache.kafka.common.header.Headers headers = pr.headers();
            SpanContext ctx = span.getContext();
            String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-01";
            headers.add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
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
            this.span = span;
            this.scope = scope;
        }
    }
}
