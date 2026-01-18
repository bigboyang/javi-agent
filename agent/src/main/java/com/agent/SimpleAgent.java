package com.agent;

import java.lang.instrument.Instrumentation;

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
        System.out.println("🎉 Simple Agent가 시작되었습니다!");
        System.out.println("🎉 ========================================");
        
        if (agentArgs != null && !agentArgs.isEmpty()) {
            System.out.println("📝 Agent 인자: " + agentArgs);
        }
        
        System.out.println("🔧 JVM 버전: " + System.getProperty("java.version"));
        System.out.println("🔧 JVM 벤더: " + System.getProperty("java.vendor"));
        
        // 클래스 변환기 등록 (나중에 추가할 기능)
        System.out.println("✅ Agent 설정이 완료되었습니다!");
        System.out.println("✅ 이제 애플리케이션이 시작됩니다...");
        System.out.println();
    }
    
    /**
     * main 메소드는 없습니다!
     * Agent는 premain 메소드로 시작됩니다.
     */
}
