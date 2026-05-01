package com.agent.instrumentation.plugin;

import com.agent.instrumentation.ControllerMethodAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class SpringMvcInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "spring-mvc";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.isAnnotatedWith(
                        ElementMatchers.named("org.springframework.web.bind.annotation.RestController"))
                    .or(ElementMatchers.isAnnotatedWith(
                        ElementMatchers.named("org.springframework.stereotype.Controller"))))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(ControllerMethodAdvice.class)
                                        .on(ElementMatchers.isMethod()
                                                .and(ElementMatchers.isPublic())
                                                .and(ElementMatchers.not(ElementMatchers.isStatic())))))
                .installOn(inst);
    }
}
