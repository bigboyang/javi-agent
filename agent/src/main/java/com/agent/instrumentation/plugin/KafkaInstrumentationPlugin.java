package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.KafkaConsumerAdvice;
import com.agent.instrumentation.KafkaProducerAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class KafkaInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(ElementMatchers.named("org.apache.kafka.clients.producer.KafkaProducer"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(KafkaProducerAdvice.class).on(ElementMatchers.named("doSend"))))
                .installOn(inst);

        agentBuilder
                .type(ElementMatchers.named(
                        "org.springframework.kafka.listener.adapter.MessagingMessageListenerAdapter"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(KafkaConsumerAdvice.class)
                                .on(ElementMatchers.named("onMessage")
                                        .and(ElementMatchers.takesArgument(
                                                0,
                                                ElementMatchers.named(
                                                        "org.apache.kafka.clients.consumer.ConsumerRecord"))))))
                .installOn(inst);
    }
}
