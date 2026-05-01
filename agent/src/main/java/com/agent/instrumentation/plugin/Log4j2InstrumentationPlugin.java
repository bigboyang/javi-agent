package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.Log4j2Advice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class Log4j2InstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "log4j2";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.apache.logging.log4j.core.Logger"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(Log4j2Advice.class)
                                        .on(ElementMatchers.named("log")
                                                .and(ElementMatchers.takesArgument(0,
                                                        ElementMatchers.named("org.apache.logging.log4j.core.LogEvent"))))))
                .installOn(inst);
    }
}
