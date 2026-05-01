package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JdbcStatementAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.description.type.TypeDescription;

public class JdbcStatementInstrumentationPlugin implements InstrumentationPlugin {

    /**
     * 지원 대상 JDBC 드라이버 패키지 접두사 허용 목록.
     *
     * isSubTypeOf(Statement.class)는 JVM에 로딩되는 모든 클래스의 계층 구조를 탐색한다.
     * 패키지 접두사 조건을 먼저 평가(cheap string compare)해 불필요한 supertype 탐색을 차단한다.
     *
     * 포함 기준:
     *  - 메이저 상용/오픈소스 드라이버 구현 클래스가 위치하는 패키지
     *  - HikariCP(com.zaxxer.hikari)는 제외: Proxy PS → 실 PS 위임 구조로 double-span 발생
     *    (JdbcPreparedStatementInstrumentationPlugin 참고)
     *  - Statement(DDL용 plain execute)는 HikariProxyStatement도 동일 문제가 있으므로 제외
     */
    private static final ElementMatcher.Junction<TypeDescription> JDBC_DRIVER_PACKAGES =
            ElementMatchers.nameStartsWith("com.mysql.")                    // MySQL Connector/J
            .or(ElementMatchers.nameStartsWith("org.postgresql."))          // PostgreSQL JDBC
            .or(ElementMatchers.nameStartsWith("com.microsoft.sqlserver.")) // MSSQL JDBC
            .or(ElementMatchers.nameStartsWith("oracle.jdbc."))             // Oracle JDBC
            .or(ElementMatchers.nameStartsWith("org.mariadb.jdbc."))        // MariaDB Connector/J
            .or(ElementMatchers.nameStartsWith("org.h2."))                  // H2 (테스트/임베디드)
            .or(ElementMatchers.nameStartsWith("org.sqlite."))              // SQLite JDBC
            .or(ElementMatchers.nameStartsWith("net.sourceforge.jtds."));   // jTDS (MSSQL/Sybase 레거시)

    @Override
    public String name() {
        return "jdbc-statement";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(
                    // 1단계: 패키지 접두사로 선별 (O(1) 문자열 비교 — 비용 최소)
                    JDBC_DRIVER_PACKAGES
                    // 2단계: 드라이버 패키지 내 클래스에 한해 Statement 계층 확인 (비용 큰 연산)
                    .and(ElementMatchers.isSubTypeOf(java.sql.Statement.class))
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
