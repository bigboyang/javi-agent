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
 */
public final class KafkaConsumerAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return Object recordsObj) {
        if (recordsObj == null) return;

        // ConsumerRecords<?>로 캐스팅
        org.apache.kafka.clients.consumer.ConsumerRecords<?, ?> records = (org.apache.kafka.clients.consumer.ConsumerRecords<?, ?>) recordsObj;
        if (records.isEmpty()) return;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.kafka.consumer");

        for (org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record : records) {
            String topic = record.topic();
            String spanName = topic + " process";

            // 컨텍스트 추출 (Extraction)
            SpanContext parentContext = null;
            try {
                Map<String, String> carrier = new HashMap<>();
                for (org.apache.kafka.common.header.Header header : record.headers()) {
                    if ("traceparent".equals(header.key())) {
                        carrier.put("traceparent", new String(header.value(), StandardCharsets.UTF_8));
                    }
                }
                if (!carrier.isEmpty()) {
                    parentContext = TraceContextPropagator.extractStatic(carrier, new MapTextMapGetter());
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

            span.end(); 
        }
    }
}
