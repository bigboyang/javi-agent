package com.agent;

import com.agent.instrumentation.AgentRuntime;
import com.agent.instrumentation.ApacheHttpClientAdvice;
import com.agent.instrumentation.CompletableFutureAdvice;
import com.agent.instrumentation.CompletableFutureSupplierAdvice;
import com.agent.instrumentation.ControllerMethodAdvice;
import com.agent.instrumentation.HttpServletAdvice;
import com.agent.instrumentation.ExecutorCallableAdvice;
import com.agent.instrumentation.ExecutorServiceAdvice;
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
import com.agent.instrumentation.SpringAsyncAdvice;
import com.agent.instrumentation.WebFluxHandlerAdvice;
import com.agent.instrumentation.GrpcClientAdvice;
import com.agent.instrumentation.MongoDbAdvice;
import com.agent.instrumentation.ElasticsearchAdvice;
import com.agent.instrumentation.ScheduledTaskAdvice;
import com.agent.logs.AgentLogger;
import com.agent.logs.AppLogCollector;
import com.agent.metric.HikariMetricsCollector;
import com.agent.metric.JvmMetricsCollector;
import com.agent.metric.TomcatMetricsCollector;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
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

        AgentBuilder.Listener transformListener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                AgentLogger.error("Transformation error on " + typeName + ": " + throwable.getMessage());
            }
            @Override
            public void onTransformation(net.bytebuddy.description.type.TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, net.bytebuddy.dynamic.DynamicType dynamicType) {
                AgentLogger.info("Transformed: " + typeDescription.getName());
            }
        };

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(transformListener);

        // bootstrap injection용 임시 디렉토리 — JDK 클래스 계측에 필요
        AgentBuilder bootstrapBuilder = null;
        try {
            File bootstrapTmpDir = Files.createTempDirectory("javi-bootstrap").toFile();
            bootstrapBuilder = new AgentBuilder.Default()
                    .with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(inst, bootstrapTmpDir))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(transformListener);
        } catch (IOException e) {
            AgentLogger.warn("Bootstrap injection 디렉토리 생성 실패, 비동기 계측 일부 제한: " + e.getMessage());
        }

        // 기존 및 신규 계측 설치
        installServletInstrumentation(inst, agentBuilder);      // Layer 1: Servlet (HTTP attr 보장)
        installSpringMvcInstrumentation(inst, agentBuilder);    // Layer 2: Spring MVC route 보강
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

        // 신규 계측
        installWebFluxInstrumentation(inst, agentBuilder);
        installGrpcClientInstrumentation(inst, agentBuilder);
        installMongoDbInstrumentation(inst, agentBuilder);
        installElasticsearchInstrumentation(inst, agentBuilder);
        installScheduledTaskInstrumentation(inst, agentBuilder);

        // 비동기/멀티쓰레드 계측
        if (bootstrapBuilder != null) {
            installExecutorServiceInstrumentation(inst, bootstrapBuilder);
            installCompletableFutureInstrumentation(inst, bootstrapBuilder);
        }
        installSpringAsyncInstrumentation(inst, agentBuilder);

        // 로깅 및 메트릭
        installLogbackInstrumentation(inst);
        installLog4j2Instrumentation(inst);
        AppLogCollector.install();
        JvmMetricsCollector.start();
        HikariMetricsCollector.start();
        TomcatMetricsCollector.start();

        AgentLogger.info("모든 계측이 등록되었습니다.");
        AgentLogger.info("이제 애플리케이션이 시작됩니다...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AgentLogger.info("Shutdown: 남은 span/metric/log flush 중...");
            try {
                JvmMetricsCollector.stop();
                HikariMetricsCollector.stop();
                TomcatMetricsCollector.stop();
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
     * ThreadPoolExecutor 계측 — execute(Runnable) 을 context-aware 래퍼로 교체.
     * submit(Callable) 도 계측하여 Callable 기반 비동기 작업을 추적한다.
     * bootstrapBuilder를 사용해 JDK 클래스를 계측한다.
     */
    private static void installExecutorServiceInstrumentation(Instrumentation inst, AgentBuilder bootstrapBuilder) {
        // execute(Runnable) 계측
        bootstrapBuilder
                .type(named("java.util.concurrent.ThreadPoolExecutor"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ExecutorServiceAdvice.class)
                                .on(named("execute")
                                        .and(takesArgument(0, named("java.lang.Runnable"))))))
                .installOn(inst);

        // submit(Callable) 계측 (AbstractExecutorService)
        bootstrapBuilder
                .type(named("java.util.concurrent.AbstractExecutorService"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ExecutorCallableAdvice.class)
                                .on(named("submit")
                                        .and(takesArgument(0, named("java.util.concurrent.Callable"))))))
                .installOn(inst);

        AgentLogger.info("ExecutorService (ThreadPool) 비동기 계측 등록 완료");
    }

    /**
     * CompletableFuture 계측 — runAsync / supplyAsync에서 ForkJoinPool으로 넘어가는
     * Runnable/Supplier를 context-aware 래퍼로 교체한다.
     */
    private static void installCompletableFutureInstrumentation(Instrumentation inst, AgentBuilder bootstrapBuilder) {
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

        AgentLogger.info("CompletableFuture 비동기 계측 등록 완료");
    }

    /**
     * Spring @Async 계측 — AsyncExecutionInterceptor.invoke() 에서
     * 부모 span을 기록하는 submit span을 생성한다.
     * 실제 워커 스레드 실행은 ExecutorServiceAdvice가 처리한다.
     */
    private static void installSpringAsyncInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("org.springframework.aop.interceptor.AsyncExecutionInterceptor"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(SpringAsyncAdvice.class)
                                .on(named("invoke").and(takesArguments(1)))))
                .installOn(inst);

        AgentLogger.info("Spring @Async 계측 등록 완료");
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
     * javax.servlet.http.HttpServlet 계측 — Layer 1 (Servlet).
     *
     * <p>HttpServlet의 모든 서브클래스(DispatcherServlet 포함)의
     * {@code service(HttpServletRequest, HttpServletResponse)}를 계측한다.
     *
     * <ul>
     *   <li>SERVER span 생성, http.request.method / http.target / http.scheme / http.host 설정</li>
     *   <li>W3C traceparent 추출</li>
     *   <li>ACTIVE_SERVLET_STATE ThreadLocal에 상태 저장 → Layer 2(ControllerMethodAdvice)가 참조</li>
     * </ul>
     */
    private static void installServletInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.hasSuperType(
                        ElementMatchers.named("javax.servlet.http.HttpServlet"))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpServletAdvice.class)
                                        .on(ElementMatchers.named("service")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(ElementMatchers.takesArgument(0,
                                                        ElementMatchers.named("javax.servlet.http.HttpServletRequest")))
                                                .and(ElementMatchers.takesArgument(1,
                                                        ElementMatchers.named("javax.servlet.http.HttpServletResponse"))))))
                .installOn(inst);
        AgentLogger.info("Servlet 계측 등록 완료 (HttpServlet.service — Layer 1)");
    }

    /**
     * Spring MVC @RestController / @Controller 계측 — Layer 2 (route 보강).
     *
     * <p>HttpServletAdvice(Layer 1)가 생성한 servlet span에 http.route를 추가하고
     * span 이름을 route 템플릿으로 갱신한다. RequestContextHolder에 의존하지 않는다.
     * Layer 1 span이 없는 환경(테스트, 비servlet)에서는 독립 SERVER span을 생성한다.
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
     *
     * Bug fix: ElementMatcher 연산자 우선순위 — .and()는 바로 앞의 .or() 결과에만 바인딩되므로
     * 각 메서드명 조건에 개별적으로 .and(takesArgument) 를 적용한다.
     * 잘못된 형태: named("execute").or(named("executeQuery")).or(named("executeUpdate")).and(takesArgument(0, String.class))
     *   → executeUpdate().and(takesArgument(0, String.class)) 만 필터링됨
     * 올바른 형태: (named("A").or(named("B")).or(named("C"))).and(takesArgument(0, String.class))
     *   → 괄호 위치가 없으므로 아래처럼 각각 and 조합 후 or로 묶어야 한다.
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
                                        .on((ElementMatchers.named("execute")
                                                    .and(ElementMatchers.takesArgument(0, String.class)))
                                                .or(ElementMatchers.named("executeQuery")
                                                    .and(ElementMatchers.takesArgument(0, String.class)))
                                                .or(ElementMatchers.named("executeUpdate")
                                                    .and(ElementMatchers.takesArgument(0, String.class))))))
                .installOn(inst);
        AgentLogger.info("JDBC Statement 계측 등록 완료 (execute*(String sql))");
    }

    /**
     * JDBC PreparedStatement execute*() 계측 (SQL 인자 없음).
     * JPA/Hibernate가 생성하는 파라미터화된 쿼리를 캡처한다.
     *
     * Bug fix 1: ElementMatcher 연산자 우선순위 — 각 메서드에 개별적으로
     *   .and(takesNoArguments()) 를 적용한 뒤 or 로 결합한다.
     *
     * Bug fix 2: HikariCP + H2 이중 계측 방지 — HikariProxyPreparedStatement.execute()는
     *   내부에서 delegate(H2 JdbcPreparedStatement).execute()를 호출하므로,
     *   두 클래스 모두 isSubTypeOf(PreparedStatement)에 매칭되어 span이 두 번 생성된다.
     *   HikariCP 프록시 클래스를 type matcher에서 명시적으로 제외하여
     *   실제 드라이버 구현체(H2, MySQL 등)의 execute() 한 곳에서만 span을 생성한다.
     */
    private static void installJdbcPreparedStatementInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(
                    ElementMatchers.isSubTypeOf(java.sql.PreparedStatement.class)
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                        // HikariCP 프록시는 제외: HikariProxyPreparedStatement.execute()가
                        // delegate(실 PS).execute()를 호출하므로 실 PS에서만 계측한다.
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
        AgentLogger.info("JDBC PreparedStatement 계측 등록 완료 (execute*(), HikariCP 프록시 제외)");
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
     * Spring WebFlux DispatcherHandler 계측.
     * handle(ServerWebExchange, Object) — 모든 WebFlux 요청의 동기 진입점.
     * W3C traceparent 추출 및 SERVER span 생성.
     */
    private static void installWebFluxInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("org.springframework.web.reactive.DispatcherHandler"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(WebFluxHandlerAdvice.class)
                                .on(named("handle")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange"))))))
                .installOn(inst);
        AgentLogger.info("Spring WebFlux 계측 등록 완료 (DispatcherHandler.handle)");
    }

    /**
     * gRPC 클라이언트 계측.
     * ClientCallImpl.start()는 blocking/async/streaming 모든 gRPC 호출의 공통 진입점.
     * MethodDescriptor에서 서비스/메서드명 추출, traceparent를 Metadata에 주입.
     */
    private static void installGrpcClientInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("io.grpc.internal.ClientCallImpl"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(GrpcClientAdvice.class)
                                .on(named("start").and(takesArguments(2)))))
                .installOn(inst);
        AgentLogger.info("gRPC Client 계측 등록 완료 (ClientCallImpl.start)");
    }

    /**
     * MongoDB 드라이버 계측.
     * InternalStreamConnection.sendAndReceive() — 동기 드라이버의 모든 command가 통과하는 지점.
     * CommandMessage에서 operation 이름과 컬렉션명 추출.
     */
    private static void installMongoDbInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("com.mongodb.internal.connection.InternalStreamConnection"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(MongoDbAdvice.class)
                                .on(named("sendAndReceive").and(takesArgument(0,
                                        named("com.mongodb.internal.connection.CommandMessage"))))))
                .installOn(inst);
        AgentLogger.info("MongoDB 계측 등록 완료 (InternalStreamConnection.sendAndReceive)");
    }

    /**
     * Elasticsearch REST Client 계측.
     * RestClient.performRequest(Request) — 모든 ES 고수준 클라이언트가 위임하는 단일 진입점.
     * Request 객체에서 HTTP 메서드와 엔드포인트 추출.
     */
    private static void installElasticsearchInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("org.elasticsearch.client.RestClient"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ElasticsearchAdvice.class)
                                .on(named("performRequest")
                                        .and(takesArguments(1))
                                        .and(takesArgument(0, named("org.elasticsearch.client.Request"))))))
                .installOn(inst);
        AgentLogger.info("Elasticsearch 계측 등록 완료 (RestClient.performRequest)");
    }

    /**
     * Spring @Scheduled 태스크 계측.
     * ScheduledMethodRunnable.run() — @Scheduled 메서드의 래퍼 Runnable.
     * 새로운 root span(독립 traceId) 생성 — 외부 요청과 무관한 독립 trace.
     */
    private static void installScheduledTaskInstrumentation(Instrumentation inst, AgentBuilder agentBuilder) {
        agentBuilder
                .type(named("org.springframework.scheduling.support.ScheduledMethodRunnable"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ScheduledTaskAdvice.class)
                                .on(named("run").and(takesNoArguments()))))
                .installOn(inst);
        AgentLogger.info("Spring @Scheduled 계측 등록 완료 (ScheduledMethodRunnable.run)");
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
