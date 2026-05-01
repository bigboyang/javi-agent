package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.WebFluxHandlerAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class WebFluxInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "webflux";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("org.springframework.web.reactive.DispatcherHandler"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(WebFluxHandlerAdvice.class)
                                .on(named("handle")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange"))))))
                .installOn(inst);
    }
}
