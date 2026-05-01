package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JavaHttpClientAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class JavaHttpClientInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "java-http-client";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(ElementMatchers.named("jdk.internal.net.http.HttpClientImpl"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(JavaHttpClientAdvice.class).on(ElementMatchers.named("send"))))
                .installOn(inst);
    }
}
