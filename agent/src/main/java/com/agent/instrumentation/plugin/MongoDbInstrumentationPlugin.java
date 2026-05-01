package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.MongoDbAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class MongoDbInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "mongodb";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("com.mongodb.internal.connection.InternalStreamConnection"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(MongoDbAdvice.class)
                                .on(named("sendAndReceive").and(takesArgument(0,
                                        named("com.mongodb.internal.connection.CommandMessage"))))))
                .installOn(inst);
    }
}
