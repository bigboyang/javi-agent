package com.agent.instrumentation;

import com.agent.propagation.MapTextMapGetter;
import com.agent.propagation.TraceContextPropagator;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer 계측.
 *
 * 추가 속성:
 *   messaging.kafka.source.partition  — 레코드가 consume된 파티션
 *   messaging.kafka.message.offset    — 레코드 오프셋
 *   messaging.message_id              — 레코드 키 (null이면 생략)
 */
public final class KafkaConsumerAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return Object recordsObj) {
        if (recordsObj == null) return;

        org.apache.kafka.clients.consumer.ConsumerRecords<?, ?> records =
                (org.apache.kafka.clients.consumer.ConsumerRecords<?, ?>) recordsObj;
        if (records.isEmpty()) return;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.kafka.consumer");

        for (org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record : records) {
            String topic    = record.topic();
            String spanName = topic + " process";

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

            com.agent.span.SpanBuilder builder = tracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.CONSUMER);
            if (parentContext != null && parentContext.isValid()) {
                builder.setParent(parentContext);
            }

            Span span = builder.startSpan();
            span.setAttribute("messaging.system", "kafka");
            span.setAttribute("messaging.destination", topic);
            span.setAttribute("messaging.operation", "process");
            span.setAttribute("messaging.kafka.source.partition",
                    String.valueOf(record.partition()));
            span.setAttribute("messaging.kafka.message.offset",
                    String.valueOf(record.offset()));

            Object key = record.key();
            if (key != null) span.setAttribute("messaging.message_id", key.toString());

            span.end();
        }
    }

    private KafkaConsumerAdvice() {}
}
