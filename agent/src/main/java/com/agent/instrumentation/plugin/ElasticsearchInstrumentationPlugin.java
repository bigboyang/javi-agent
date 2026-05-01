package com.agent.instrumentation.plugin;

import com.agent.instrumentation.ElasticsearchAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class ElasticsearchInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("org.elasticsearch.client.RestClient"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ElasticsearchAdvice.class)
                                .on(named("performRequest")
                                        .and(takesArguments(1))
                                        .and(takesArgument(0, named("org.elasticsearch.client.Request"))))))
                .installOn(inst);
    }
}
