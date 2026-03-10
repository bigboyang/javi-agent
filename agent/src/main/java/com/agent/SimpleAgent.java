package com.agent;

import com.agent.instrumentation.AgentRuntime;
import com.agent.instrumentation.ApacheHttpClientAdvice;
import com.agent.instrumentation.ControllerMethodAdvice;
import com.agent.instrumentation.HttpClientAdvice;
import com.agent.instrumentation.HttpRequestExecuteAdvice;
import com.agent.instrumentation.JavaHttpClientAdvice;
import com.agent.instrumentation.JdbcPreparedStatementAdvice;
import com.agent.instrumentation.JdbcStatementAdvice;
import com.agent.instrumentation.KafkaConsumerAdvice;
import com.agent.instrumentation.KafkaProducerAdvice;
import com.agent.instrumentation.Log4j2Advice;
import com.agent.instrumentation.LogbackAdvice;
import com.agent.instrumentation.RabbitMQAdvice;
import com.agent.instrumentation.RedisLettuceAdvice;
import com.agent.logs.AgentLogger;
import com.agent.logs.AppLogCollector;
import com.agent.metric.HikariMetricsCollector;
import com.agent.metric.JvmMetricsCollector;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Java Agent 진입점.
 *
 * 자동 계측 범위:
 *  - Spring MVC: @RestController / @Controller 전체
 *  - JDBC: Statement / PreparedStatement
 *  - HTTP Client: RestTemplate, Apache HttpClient, Java 11 HttpClient
 *  - Messaging: Kafka Producer/Consumer
 *  - NoSQL: Redis (Lettuce)
 *  - Logging: Logback, Log4j2
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

        // AgentRuntime 초기화 강제
        AgentRuntime.provider();

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                        AgentLogger.error("Transformation error on " + typeName + ": " + throwable.getMessage());
                    }
                    @Override
                    public void onTransformation(net.bytebuddy.description.type.TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, net.bytebuddy.dynamic.DynamicType dynamicType) {
                        AgentLogger.info("Transformed: " + typeDescription.getName());
                    }
                });

        // 기존 및 신규 계측 설치
        installSpringMvcInstrumentation(inst, agentBuilder);
        installJdbcStatementInstrumentation(inst, agentBuilder);
        installJdbcPreparedStatementInstrumentation(inst, agentBuilder);
        installHttpClientInstrumentation(inst, agentBuilder);
        installHttpRequestPropagation(inst, agentBuilder);
        
        // 추가 계측
        installApacheHttpClientInstrumentation(inst, agentBuilder);
        installJavaHttpClientInstrumentation(inst, agentBuilder);
        installKafkaInstrumentation(inst, agentBuilder);
        installRedisInstrumentation(inst, agentBuilder);
        installRabbitMQInstrumentation(inst, agentBuilder);

        // 로깅 및 메트릭
        installLogbackInstrumentation(inst);
        installLog4j2Instrumentation(inst);
        AppLogCollector.install();
        JvmMetricsCollector.start();
        HikariMetricsCollector.start();

        AgentLogger.info("모든 계측이 등록되었습니다.");
        AgentLogger.info("이제 애플리케이션이 시작됩니다...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AgentLogger.info("Shutdown: 남은 span/metric/log flush 중...");
            try {
                JvmMetricsCollector.stop();
                HikariMetricsCollector.stop();
                AgentRuntime.provider().forceFlush().join(5, TimeUnit.SECONDS);
                AgentRuntime.provider().shutdown().join(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                AgentLogger.error("Shutdown 중 오류: " + e.getMessage(), e);
            }
            AgentLogger.info("Shutdown 완료.");
            AgentLogger.flush();
        }, "javi-agent-shutdown"));
    }

    private static void installApacheHttpClientInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(hasSuperType(named("org.apache.http.client.HttpClient")))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ApacheHttpClientAdvice.class).on(named("doExecute"))))
                .installOn(inst);
        AgentLogger.info("Apache HttpClient 계측 등록 완료");
    }

    private static void installJavaHttpClientInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("jdk.internal.net.http.HttpClientImpl"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(JavaHttpClientAdvice.class).on(named("send"))))
                .installOn(inst);
        AgentLogger.info("Java 11 HttpClient 계측 등록 완료");
    }

    private static void installKafkaInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        // Producer
        agentBuilder
                .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(KafkaProducerAdvice.class).on(named("doSend"))))
                .installOn(inst);

        // Consumer
        agentBuilder
                .type(named("org.apache.kafka.clients.consumer.KafkaConsumer"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(KafkaConsumerAdvice.class).on(named("poll").and(takesArguments(1).or(takesArguments(2))))))
                .installOn(inst);
        AgentLogger.info("Kafka Producer/Consumer 계측 등록 완료");
    }

    private static void installRedisInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("io.lettuce.core.protocol.CommandHandler")
                    .or(named("io.lettuce.core.RedisChannelHandler")))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RedisLettuceAdvice.class).on(named("dispatch"))))
                .installOn(inst);
        AgentLogger.info("Redis (Lettuce) 계측 등록 완료");
    }

    private static void installRabbitMQInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        // Producer
        agentBuilder
                .type(named("org.springframework.amqp.rabbit.core.RabbitTemplate"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RabbitMQAdvice.Producer.class).on(named("doSend"))))
                .installOn(inst);

        // Consumer
        agentBuilder
                .type(named("org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RabbitMQAdvice.Consumer.class).on(named("executeListener"))))
                .installOn(inst);
        AgentLogger.info("RabbitMQ (Spring AMQP) 계측 등록 완료");
    }

    /**
     * Spring MVC @RestController / @Controller 계측.
     * 모든 public 메서드에 SERVER 스팬을 생성한다.
     */
    private static void installSpringMvcInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
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
    private static void installJdbcStatementInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
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
    private static void installJdbcPreparedStatementInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
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
    private static void installHttpClientInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(ElementMatchers.named("org.springframework.web.client.RestTemplate"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpClientAdvice.class)
                                        .on(ElementMatchers.named("doExecute"))))
                .installOn(inst);
        AgentLogger.info("HTTP Client 계측 등록 완료 (RestTemplate.doExecute)");
    }

    /**
     * ClientHttpRequest.execute() 계측 — W3C traceparent 헤더 outgoing inject.
     * RestTemplate이 실제 HTTP 전송 직전에 헤더를 설정한다.
     */
    private static void installHttpRequestPropagation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.springframework.http.client.ClientHttpRequest"))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpRequestExecuteAdvice.class)
                                        .on(ElementMatchers.named("execute")
                                                .and(ElementMatchers.takesNoArguments()))))
                .installOn(inst);
        AgentLogger.info("HTTP Request 전파 계측 등록 완료 (ClientHttpRequest.execute → traceparent inject)");
    }

    /**
     * Logback 계측.
     * MDC 주입 및 로그 메시지 캡처.
     */
    private static void installLogbackInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("ch.qos.logback.classic.Logger"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(LogbackAdvice.class)
                                        .on(ElementMatchers.named("callAppenders")
                                                .and(ElementMatchers.takesArgument(0, ElementMatchers.named("ch.qos.logback.classic.spi.ILoggingEvent"))))))
                .installOn(inst);
        AgentLogger.info("Logback 계측 등록 완료 (MDC injection & Message capture)");
    }

    /**
     * Log4j2 계측.
     * ThreadContext 주입 및 로그 메시지 캡처.
     */
    private static void installLog4j2Instrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.apache.logging.log4j.core.Logger"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(Log4j2Advice.class)
                                        .on(ElementMatchers.named("log")
                                                .and(ElementMatchers.takesArgument(0, ElementMatchers.named("org.apache.logging.log4j.core.LogEvent"))))))
                .installOn(inst);
        AgentLogger.info("Log4j2 계측 등록 완료 (ThreadContext injection & Message capture)");
    }
}
