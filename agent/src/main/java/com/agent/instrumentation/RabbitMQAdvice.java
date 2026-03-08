package com.agent.instrumentation;

import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * RabbitMQ (Spring AMQP) 계측.
 */
public final class RabbitMQAdvice {

    public static final class Producer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static State onEnter(@Advice.Argument(1) String routingKey, @Advice.Argument(2) Object message) {
            Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.rabbitmq.producer");
            String spanName = "RabbitMQ send " + (routingKey != null ? routingKey : "default");

            Span span = tracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.PRODUCER)
                    .startSpan();

            span.setAttribute("messaging.system", "rabbitmq");
            span.setAttribute("messaging.destination", routingKey);
            span.setAttribute("messaging.operation", "send");

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
    }

    public static final class Consumer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static State onEnter(@Advice.Argument(0) Object container, @Advice.Argument(1) Object messageObj) {
            if (messageObj == null) return null;

            // Spring AMQP Message 클래스로 캐스팅
            org.springframework.amqp.core.Message message = (org.springframework.amqp.core.Message) messageObj;
            
            Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.rabbitmq.consumer");
            org.springframework.amqp.core.MessageProperties props = message.getMessageProperties();
            String queue = props.getConsumerQueue();
            String spanName = "RabbitMQ process " + (queue != null ? queue : "unknown");

            Span span = tracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();

            span.setAttribute("messaging.system", "rabbitmq");
            span.setAttribute("messaging.destination", queue);
            span.setAttribute("messaging.operation", "process");

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
