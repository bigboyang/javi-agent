package com.agent.instrumentation.plugin;

import com.agent.instrumentation.HttpRequestExecuteAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class HttpRequestPropagationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "http-request-propagation";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.springframework.http.client.ClientHttpRequest"))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpRequestExecuteAdvice.class)
                                        .on(ElementMatchers.named("execute")
                                                .and(ElementMatchers.takesNoArguments()))))
                .installOn(inst);
    }
}
