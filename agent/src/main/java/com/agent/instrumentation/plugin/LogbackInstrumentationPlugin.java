package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.LogbackAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class LogbackInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "logback";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("ch.qos.logback.classic.Logger"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(LogbackAdvice.class)
                                        .on(ElementMatchers.named("callAppenders")
                                                .and(ElementMatchers.takesArgument(0,
                                                        ElementMatchers.named("ch.qos.logback.classic.spi.ILoggingEvent"))))))
                .installOn(inst);
    }
}
