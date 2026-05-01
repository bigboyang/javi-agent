package com.agent.instrumentation.plugin;

import com.agent.instrumentation.ExecutorCallableAdvice;
import com.agent.instrumentation.ExecutorServiceAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.VirtualThreadExecutorAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class ExecutorServiceInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "executor-service";
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
                .type(named("java.util.concurrent.ThreadPoolExecutor"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ExecutorServiceAdvice.class)
                                .on(named("execute")
                                        .and(takesArgument(0, named("java.lang.Runnable"))))))
                .installOn(inst);

        bootstrapBuilder
                .type(named("java.util.concurrent.AbstractExecutorService"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ExecutorCallableAdvice.class)
                                .on(named("submit")
                                        .and(takesArgument(0, named("java.util.concurrent.Callable"))))))
                .installOn(inst);

        // Java 21 Virtual Thread per-task executor — ThreadPoolExecutor를 상속하지 않으므로 별도 계측
        bootstrapBuilder
                .type(named("java.util.concurrent.ThreadPerTaskExecutor"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(VirtualThreadExecutorAdvice.class)
                                .on(named("execute")
                                        .and(takesArgument(0, named("java.lang.Runnable"))))))
                .installOn(inst);
    }
}
