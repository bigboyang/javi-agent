package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.ScheduledTaskAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class ScheduledTaskInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "scheduled-task";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("org.springframework.scheduling.support.ScheduledMethodRunnable"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ScheduledTaskAdvice.class)
                                .on(named("run").and(takesNoArguments()))))
                .installOn(inst);
    }
}
