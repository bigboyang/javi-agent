package com.agent;

import com.agent.instrumentation.AgentRuntime;
import com.agent.instrumentation.ControllerMethodAdvice;
import com.agent.instrumentation.HttpClientAdvice;
import com.agent.instrumentation.JdbcPreparedStatementAdvice;
import com.agent.instrumentation.JdbcStatementAdvice;
import com.agent.logs.AgentLogger;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Java Agent 진입점.
 *
 * 자동 계측 범위:
 *  - Spring MVC: @RestController / @Controller 전체 (OTel HTTP 서버 시맨틱)
 *  - JDBC Statement: execute*(String sql) — 명시적 SQL 인자를 가진 쿼리
 *  - JDBC PreparedStatement: execute*() — JPA/Hibernate 등이 생성하는 파라미터화 쿼리
 *  - HTTP Client: Spring RestTemplate.doExecute() (OTel HTTP 클라이언트 시맨틱)
 */
public class SimpleAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        AgentLogger.info("========================================");
        AgentLogger.info("Javi Agent가 시작되었습니다!");
        AgentLogger.info("로그 파일: " + AgentLogger.logFilePath());
        AgentLogger.info("========================================");

        if (agentArgs != null && !agentArgs.isEmpty()) {
            AgentLogger.info("Agent 인자: " + agentArgs);
        }

        installSpringMvcInstrumentation(inst);
        installJdbcStatementInstrumentation(inst);
        installJdbcPreparedStatementInstrumentation(inst);
        installHttpClientInstrumentation(inst);

        AgentLogger.info("모든 계측이 등록되었습니다.");
        AgentLogger.info("이제 애플리케이션이 시작됩니다...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AgentLogger.info("Shutdown: 남은 span flush 중...");
            try {
                AgentRuntime.provider().forceFlush().join(5, TimeUnit.SECONDS);
                AgentRuntime.provider().shutdown().join(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                AgentLogger.error("Shutdown 중 오류: " + e.getMessage(), e);
            }
            AgentLogger.info("Shutdown 완료.");
            AgentLogger.flush();
        }, "javi-agent-shutdown"));
    }

    /**
     * Spring MVC @RestController / @Controller 계측.
     * 모든 public 메서드에 SERVER 스팬을 생성한다.
     */
    private static void installSpringMvcInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(
                    ElementMatchers.isAnnotatedWith(
                        ElementMatchers.named("org.springframework.web.bind.annotation.RestController"))
                    .or(ElementMatchers.isAnnotatedWith(
                        ElementMatchers.named("org.springframework.stereotype.Controller"))))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(ControllerMethodAdvice.class)
                                        .on(ElementMatchers.isMethod()
                                                .and(ElementMatchers.isPublic())
                                                .and(ElementMatchers.not(ElementMatchers.isStatic())))))
                .installOn(inst);
        AgentLogger.info("Spring MVC 계측 등록 완료 (@RestController, @Controller)");
    }

    /**
     * JDBC Statement execute*(String sql) 계측.
     * Statement를 직접 사용하거나 Hibernate Native Query 등에서 호출된다.
     */
    private static void installJdbcStatementInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(
                    ElementMatchers.isSubTypeOf(java.sql.Statement.class)
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(JdbcStatementAdvice.class)
                                        .on(ElementMatchers.named("execute")
                                                .or(ElementMatchers.named("executeQuery"))
                                                .or(ElementMatchers.named("executeUpdate"))
                                                .and(ElementMatchers.takesArgument(0, String.class)))))
                .installOn(inst);
        AgentLogger.info("JDBC Statement 계측 등록 완료 (execute*(String sql))");
    }

    /**
     * JDBC PreparedStatement execute*() 계측 (SQL 인자 없음).
     * JPA/Hibernate가 생성하는 파라미터화된 쿼리를 캡처한다.
     */
    private static void installJdbcPreparedStatementInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(
                    ElementMatchers.isSubTypeOf(java.sql.PreparedStatement.class)
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(JdbcPreparedStatementAdvice.class)
                                        .on(ElementMatchers.named("execute")
                                                .or(ElementMatchers.named("executeQuery"))
                                                .or(ElementMatchers.named("executeUpdate"))
                                                .and(ElementMatchers.takesNoArguments()))))
                .installOn(inst);
        AgentLogger.info("JDBC PreparedStatement 계측 등록 완료 (execute*())");
    }

    /**
     * Spring RestTemplate HTTP 클라이언트 계측.
     * doExecute() 가 모든 REST 호출의 실제 진입점이다.
     */
    private static void installHttpClientInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.springframework.web.client.RestTemplate"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpClientAdvice.class)
                                        .on(ElementMatchers.named("doExecute"))))
                .installOn(inst);
        AgentLogger.info("HTTP Client 계측 등록 완료 (RestTemplate.doExecute)");
    }
}
