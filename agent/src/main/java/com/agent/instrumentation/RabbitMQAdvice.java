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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ (Spring AMQP 3.x) 계측.
 *
 * Producer: RabbitTemplate.doSend(Channel, String exchange, String routingKey, Message, boolean, CorrelationData)
 *   - Arg 1 = exchange, Arg 2 = routingKey, Arg 3 = Message
 *   - Arg 2(String)를 Message로 잘못 캡처하면 instanceof 체크가 항상 실패하여 traceparent 미주입
 *
 * Consumer: AbstractMessageListenerContainer.executeListener(Channel, Object data)
 *   - Arg 1 = data (Message 또는 List<Message>)
 *   - 배치 모드에서 List 수신 시 단순 캐스트는 ClassCastException 발생
 */
public final class RabbitMQAdvice {

    public static final class Producer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static State onEnter(
                @Advice.Argument(1) String exchange,
                @Advice.Argument(2) String routingKey,
                @Advice.Argument(3) org.springframework.amqp.core.Message message) {

            Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.rabbitmq.producer");
            String dest = (exchange != null && !exchange.isEmpty()) ? exchange
                    : (routingKey != null ? routingKey : "default");
            String spanName = "RabbitMQ send " + dest;

            Span span = tracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.PRODUCER)
                    .startSpan();

            span.setAttribute("messaging.system", "rabbitmq");
            span.setAttribute("messaging.destination.name", dest);
            span.setAttribute("messaging.operation", "send");
            if (routingKey != null && !routingKey.isEmpty()) {
                span.setAttribute("messaging.rabbitmq.destination.routing_key", routingKey);
            }

            // W3C traceparent 주입 — message는 이제 실제 Message 타입으로 직접 캡처됨
            if (message != null) {
                SpanContext ctx = span.getContext();
                if (ctx != null && ctx.isValid()) {
                    try {
                        String sampledFlag = span.isRecording() ? "01" : "00";
                        String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + sampledFlag;
                        message.getMessageProperties().setHeader("traceparent", traceparent);
                    } catch (Throwable ignored) {}
                }
            }

            return new State(span, span.makeCurrent(), null, null);
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
        }
    }

    public static final class Consumer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static State onEnter(@Advice.Argument(1) Object dataObj) {
            org.springframework.amqp.core.Message message = null;
            if (dataObj instanceof org.springframework.amqp.core.Message) {
                message = (org.springframework.amqp.core.Message) dataObj;
            } else if (dataObj instanceof List) {
                List<?> batch = (List<?>) dataObj;
                if (!batch.isEmpty() && batch.get(0) instanceof org.springframework.amqp.core.Message) {
                    // 배치 모드: 첫 번째 메시지의 trace context로 consumer span 생성
                    message = (org.springframework.amqp.core.Message) batch.get(0);
                }
            }
            if (message == null) return null;

            org.springframework.amqp.core.MessageProperties props = message.getMessageProperties();
            String queue = props.getConsumerQueue();
            String spanName = "RabbitMQ process " + (queue != null ? queue : "unknown");

            // W3C traceparent + tracestate 추출
            SpanContext parentCtx = null;
            try {
                Map<String, Object> headers = props.getHeaders();
                if (headers != null) {
                    Object tp = headers.get("traceparent");
                    if (tp != null) {
                        Map<String, String> carrier = new HashMap<>();
                        carrier.put("traceparent", tp.toString());
                        Object ts = headers.get("tracestate");
                        if (ts != null) {
                            carrier.put("tracestate", ts.toString());
                        }
                        parentCtx = TraceContextPropagator.extractStatic(carrier, new MapTextMapGetter());
                    }
                }
            } catch (Throwable ignored) {}

            Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.rabbitmq.consumer");
            com.agent.span.SpanBuilder builder = tracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.CONSUMER);
            if (parentCtx != null && parentCtx.isValid()) {
                builder.setParent(parentCtx);
            }
            Span span = builder.startSpan();

            span.setAttribute("messaging.system", "rabbitmq");
            span.setAttribute("messaging.destination.name", queue);
            span.setAttribute("messaging.operation", "process");
            String receivedRoutingKey = props.getReceivedRoutingKey();
            if (receivedRoutingKey != null && !receivedRoutingKey.isEmpty()) {
                span.setAttribute("messaging.rabbitmq.destination.routing_key", receivedRoutingKey);
            }
            String receivedExchange = props.getReceivedExchange();
            if (receivedExchange != null && !receivedExchange.isEmpty()) {
                span.setAttribute("messaging.rabbitmq.destination.exchange", receivedExchange);
            }

            Scope scope = span.makeCurrent();

            // MDC 주입 — 리스너 실행 중 로그에 traceId/spanId 포함
            String prevTraceId = MDC.get("traceId");
            String prevSpanId = MDC.get("spanId");
            SpanContext ctx = span.getContext();
            if (ctx != null && ctx.isValid()) {
                MDC.put("traceId", ctx.getTraceId());
                MDC.put("spanId", ctx.getSpanId());
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

            // MDC 복원 — 컨슈머 스레드 풀 재사용 시 반드시 원복
            if (state.prevTraceId == null) MDC.remove("traceId");
            else MDC.put("traceId", state.prevTraceId);
            if (state.prevSpanId == null) MDC.remove("spanId");
            else MDC.put("spanId", state.prevSpanId);
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

    private RabbitMQAdvice() {}
}
