package com.agent.instrumentation.plugin;

import com.agent.instrumentation.HttpClientAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class RestTemplateInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "rest-template";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(ElementMatchers.named("org.springframework.web.client.RestTemplate"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpClientAdvice.class)
                                        .on(ElementMatchers.named("doExecute"))))
                .installOn(inst);
    }
}
