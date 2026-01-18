package com.apmtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 메인 애플리케이션 클래스
 */
@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        System.out.println("🚀 Spring Boot 애플리케이션을 시작합니다...");
        SpringApplication.run(TestApplication.class, args);
        System.out.println("✅ Spring Boot 애플리케이션이 성공적으로 시작되었습니다!");
        System.out.println("🌐 서버 주소: http://localhost:8080");
        System.out.println("📊 H2 콘솔: http://localhost:8080/h2-console");
        System.out.println("🔗 API 엔드포인트: http://localhost:8080/api/users");
    }
}
