package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.SpringAsyncAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class SpringAsyncInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "spring-async";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("org.springframework.aop.interceptor.AsyncExecutionInterceptor"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(SpringAsyncAdvice.class)
                                .on(named("invoke").and(takesArguments(1)))))
                .installOn(inst);
    }
}
