package com.agent.instrumentation.plugin;

import com.agent.instrumentation.CompletableFutureAdvice;
import com.agent.instrumentation.CompletableFutureSupplierAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class CompletableFutureInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "completable-future";
    }

    @Override
    public boolean requiresBootstrap() {
        return true;
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        if (bootstrapBuilder == null) {
            return;
        }

        bootstrapBuilder
                .type(named("java.util.concurrent.CompletableFuture"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder
                            .visit(Advice.to(CompletableFutureAdvice.class)
                                    .on(named("runAsync")
                                            .and(isStatic())
                                            .and(takesArgument(0, named("java.lang.Runnable")))))
                            .visit(Advice.to(CompletableFutureSupplierAdvice.class)
                                    .on(named("supplyAsync")
                                            .and(isStatic())
                                            .and(takesArgument(0, named("java.util.function.Supplier"))))))
                .installOn(inst);
    }
}
