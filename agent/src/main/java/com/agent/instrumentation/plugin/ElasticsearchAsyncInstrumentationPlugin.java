package com.agent.instrumentation.plugin;

import com.agent.instrumentation.ElasticsearchAsyncAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class ElasticsearchAsyncInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "elasticsearch-async";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("org.elasticsearch.client.RestClient"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ElasticsearchAsyncAdvice.class)
                                .on(named("performRequestAsync")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("org.elasticsearch.client.Request"))))))
                .installOn(inst);
    }
}
