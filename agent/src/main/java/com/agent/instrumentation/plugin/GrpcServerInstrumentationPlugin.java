package com.agent.instrumentation.plugin;

import com.agent.instrumentation.GrpcServerAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class GrpcServerInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "grpc-server";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(nameContains("ServerCalls$")
                        .and(hasSuperType(named("io.grpc.ServerCall$Listener"))))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(GrpcServerAdvice.class)
                                .on(named("onHalfClose").and(takesNoArguments()))))
                .installOn(inst);
    }
}
