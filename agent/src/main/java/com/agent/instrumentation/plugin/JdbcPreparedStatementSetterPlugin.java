package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JdbcPreparedStatementSetterAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.description.type.TypeDescription;

/**
 * PreparedStatement의 setXxx() / clearParameters() 메서드를 계측하여
 * execute() 시점에 바인딩 파라미터를 스팬 속성으로 기록할 수 있도록 한다.
 *
 * JdbcPreparedStatementInstrumentationPlugin과 동일한 드라이버 패키지를 대상으로 한다.
 * order=90으로 execute() 계측 플러그인(default 100)보다 먼저 설치된다.
 */
public class JdbcPreparedStatementSetterPlugin implements InstrumentationPlugin {

    private static final ElementMatcher.Junction<TypeDescription> JDBC_DRIVER_PACKAGES =
            ElementMatchers.nameStartsWith("com.mysql.")
            .or(ElementMatchers.nameStartsWith("org.postgresql."))
            .or(ElementMatchers.nameStartsWith("com.microsoft.sqlserver."))
            .or(ElementMatchers.nameStartsWith("oracle.jdbc."))
            .or(ElementMatchers.nameStartsWith("org.mariadb.jdbc."))
            .or(ElementMatchers.nameStartsWith("org.h2."))
            .or(ElementMatchers.nameStartsWith("org.sqlite."))
            .or(ElementMatchers.nameStartsWith("net.sourceforge.jtds."));

    @Override
    public String name() {
        return "jdbc-prepared-statement-setter";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    JDBC_DRIVER_PACKAGES
                    .and(ElementMatchers.isSubTypeOf(java.sql.PreparedStatement.class))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(JdbcPreparedStatementSetterAdvice.class)
                                        .on(ElementMatchers.named("setString")
                                                .or(ElementMatchers.named("setInt"))
                                                .or(ElementMatchers.named("setLong"))
                                                .or(ElementMatchers.named("setDouble"))
                                                .or(ElementMatchers.named("setFloat"))
                                                .or(ElementMatchers.named("setBoolean"))
                                                .or(ElementMatchers.named("setShort"))
                                                .or(ElementMatchers.named("setByte"))
                                                .or(ElementMatchers.named("setBigDecimal"))
                                                .or(ElementMatchers.named("setDate"))
                                                .or(ElementMatchers.named("setTime"))
                                                .or(ElementMatchers.named("setTimestamp"))
                                                .or(ElementMatchers.named("setObject"))
                                                .or(ElementMatchers.named("setNull"))
                                                .or(ElementMatchers.named("clearParameters")))))
                .installOn(inst);
    }
}
