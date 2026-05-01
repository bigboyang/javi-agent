package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JdbcPreparedStatementAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class JdbcPreparedStatementInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "jdbc-prepared-statement";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.isSubTypeOf(java.sql.PreparedStatement.class)
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                        // HikariCP 프록시는 제외: delegate(실 PS).execute()를 호출하므로 실 PS에서만 계측
                        .and(ElementMatchers.not(
                            ElementMatchers.nameStartsWith("com.zaxxer.hikari."))))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(JdbcPreparedStatementAdvice.class)
                                        .on((ElementMatchers.named("execute")
                                                    .and(ElementMatchers.takesNoArguments()))
                                                .or(ElementMatchers.named("executeQuery")
                                                    .and(ElementMatchers.takesNoArguments()))
                                                .or(ElementMatchers.named("executeUpdate")
                                                    .and(ElementMatchers.takesNoArguments())))))
                .installOn(inst);
    }
}
