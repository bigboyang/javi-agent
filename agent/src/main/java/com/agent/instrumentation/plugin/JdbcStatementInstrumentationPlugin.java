package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JdbcStatementAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class JdbcStatementInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "jdbc-statement";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.isSubTypeOf(java.sql.Statement.class)
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(JdbcStatementAdvice.class)
                                        .on((ElementMatchers.named("execute")
                                                    .and(ElementMatchers.takesArgument(0, String.class)))
                                                .or(ElementMatchers.named("executeQuery")
                                                    .and(ElementMatchers.takesArgument(0, String.class)))
                                                .or(ElementMatchers.named("executeUpdate")
                                                    .and(ElementMatchers.takesArgument(0, String.class))))))
                .installOn(inst);
    }
}
