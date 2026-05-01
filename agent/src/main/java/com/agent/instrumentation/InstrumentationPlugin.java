package com.agent.instrumentation;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;

public interface InstrumentationPlugin {

    void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder);

    default int order() {
        return 100;
    }

    String name();

    default boolean requiresBootstrap() {
        return false;
    }
}
