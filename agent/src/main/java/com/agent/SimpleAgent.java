package com.agent;

import com.agent.instrumentation.AgentRuntime;
import com.agent.instrumentation.ControllerMethodAdvice;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * 가장 간단한 Java Agent 예제
 * 
 * 핵심 포인트:
 * 1. main 메소드가 아님!
 * 2. premain 메소드가 있음 (JVM이 자동 호출)
 * 3. JVM 시작 시 자동으로 로드됨
 */
public class SimpleAgent {
    
    /**
     * JVM이 시작될 때 자동으로 호출되는 메소드
     * 
     * @param agentArgs -javaagent 옵션으로 전달된 인자
     * @param inst JVM의 Instrumentation 객체
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("🎉 ========================================");
        System.out.println("🎉 Javi Agent가 시작되었습니다!");
        System.out.println("🎉 ========================================");
        
        if (agentArgs != null && !agentArgs.isEmpty()) {
            System.out.println("📝 Agent 인자: " + agentArgs);
        }
        
        System.out.println("🔧 JVM 버전: " + System.getProperty("java.version"));
        System.out.println("🔧 JVM 벤더: " + System.getProperty("java.vendor"));

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.nameContains("UserController"))
                .transform(
                        (builder, type, classLoader, module, protectionDomain) ->
                                builder.visit(
                                        Advice.to(ControllerMethodAdvice.class)
                                                .on(ElementMatchers.isMethod()
                                                        .and(ElementMatchers.isPublic()))))
                .installOn(inst);

        System.out.println("✅ Controller 자동 계측이 등록되었습니다!");
        System.out.println("✅ Agent 설정이 완료되었습니다!");
        System.out.println("✅ 이제 애플리케이션이 시작됩니다...");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[javi-agent] Shutdown: 남은 span flush 중...");
            try {
                AgentRuntime.provider().forceFlush().join(5, TimeUnit.SECONDS);
                AgentRuntime.provider().shutdown().join(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[javi-agent] Shutdown 중 오류: " + e.getMessage());
            }
            System.out.println("[javi-agent] Shutdown 완료.");
        }, "javi-agent-shutdown"));
    }
    
    /**
     * main 메소드는 없습니다!
     * Agent는 premain 메소드로 시작됩니다.
     */
}
