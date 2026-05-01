package com.agent.instrumentation.plugin;

import com.agent.instrumentation.GrpcClientAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class GrpcClientInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "grpc-client";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("io.grpc.internal.ClientCallImpl"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(GrpcClientAdvice.class)
                                .on(named("start").and(takesArguments(2)))))
                .installOn(inst);
    }
}
