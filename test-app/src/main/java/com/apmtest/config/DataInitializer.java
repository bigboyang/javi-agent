package com.apmtest.config;

import com.apmtest.entity.User;
import com.apmtest.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 초기 데이터를 생성하는 클래스
 */
@Component
public class DataInitializer implements CommandLineRunner {
    
    private final UserRepository userRepository;
    
    @Autowired
    public DataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    
    @Override
    public void run(String... args) throws Exception {
        // 초기 사용자 데이터 생성
        createInitialUsers();
    }
    
    private void createInitialUsers() {
        // 기존 데이터가 없을 때만 생성
        if (userRepository.count() == 0) {
            System.out.println("🚀 초기 사용자 데이터를 생성합니다...");
            
            User user1 = new User("김철수", "kim@example.com", "010-1234-5678");
            User user2 = new User("이영희", "lee@example.com", "010-2345-6789");
            User user3 = new User("박민수", "park@example.com", "010-3456-7890");
            
            userRepository.save(user1);
            userRepository.save(user2);
            userRepository.save(user3);
            
            System.out.println("✅ 초기 사용자 데이터 생성 완료!");
            System.out.println("📊 총 " + userRepository.count() + "명의 사용자가 등록되었습니다.");
        } else {
            System.out.println("📊 이미 " + userRepository.count() + "명의 사용자가 등록되어 있습니다.");
        }
    }
}
