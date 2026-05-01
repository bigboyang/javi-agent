package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.RabbitMQAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class RabbitMQInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "rabbitmq";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(ElementMatchers.named("org.springframework.amqp.rabbit.core.RabbitTemplate"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RabbitMQAdvice.Producer.class).on(ElementMatchers.named("doSend"))))
                .installOn(inst);

        agentBuilder
                .type(ElementMatchers.named("org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RabbitMQAdvice.Consumer.class).on(ElementMatchers.named("executeListener"))))
                .installOn(inst);
    }
}
