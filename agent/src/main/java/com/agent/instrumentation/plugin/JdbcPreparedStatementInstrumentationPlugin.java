package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JdbcPreparedStatementAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.description.type.TypeDescription;

public class JdbcPreparedStatementInstrumentationPlugin implements InstrumentationPlugin {

    /**
     * Statement 플러그인과 동일한 드라이버 허용 목록.
     * HikariCP(com.zaxxer.hikari)는 원천적으로 포함하지 않는다 —
     * 부정(not) 조건 대신 긍정(허용) 목록으로 표현해 의도를 명확히 한다.
     */
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
        return "jdbc-prepared-statement";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    // 1단계: 드라이버 패키지 접두사 허용 목록 (HikariCP 자동 제외)
                    JDBC_DRIVER_PACKAGES
                    // 2단계: 패키지 필터 통과 후 PreparedStatement 계층 확인
                    .and(ElementMatchers.isSubTypeOf(java.sql.PreparedStatement.class))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
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
