package com.agent.instrumentation.plugin;

import com.agent.instrumentation.ApacheHttpClientAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ApacheHttpClientInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "apache-http-client";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("org.apache.http.client.HttpClient")))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ApacheHttpClientAdvice.class).on(ElementMatchers.named("doExecute"))))
                .installOn(inst);
    }
}
