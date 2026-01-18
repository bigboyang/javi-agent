package com.agent;

/**
 * SimpleAgent를 테스트하기 위한 애플리케이션
 */
public class TestApp {
    
    public static void main(String[] args) {
        System.out.println("🚀 TestApp이 시작되었습니다!");
        System.out.println("🚀 현재 시간: " + java.time.LocalDateTime.now());
        System.out.println();
        
        TestApp app = new TestApp();
        app.doSomething();
        app.doSomethingElse();
        
        System.out.println("🏁 TestApp이 종료되었습니다!");
    }
    
    public void doSomething() {
        System.out.println("📋 doSomething() 메소드 실행 중...");
        try {
            Thread.sleep(1000); // 1초 대기
            System.out.println("✅ doSomething() 완료!");
        } catch (InterruptedException e) {
            System.err.println("❌ doSomething() 오류: " + e.getMessage());
        }
    }
    
    public void doSomethingElse() {
        System.out.println("📋 doSomethingElse() 메소드 실행 중...");
        try {
            Thread.sleep(500); // 0.5초 대기
            System.out.println("✅ doSomethingElse() 완료!");
        } catch (InterruptedException e) {
            System.err.println("❌ doSomethingElse() 오류: " + e.getMessage());
        }
    }
}
