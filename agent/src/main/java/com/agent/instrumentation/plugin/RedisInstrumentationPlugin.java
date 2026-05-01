package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.RedisLettuceAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class RedisInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "redis-lettuce";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(ElementMatchers.named("io.lettuce.core.protocol.CommandHandler")
                    .or(ElementMatchers.named("io.lettuce.core.RedisChannelHandler")))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RedisLettuceAdvice.class).on(ElementMatchers.named("dispatch"))))
                .installOn(inst);
    }
}
