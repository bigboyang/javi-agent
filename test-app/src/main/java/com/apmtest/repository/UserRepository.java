package com.apmtest.repository;

import com.apmtest.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * User 엔티티를 위한 JPA Repository
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * 이메일로 사용자 찾기
     */
    Optional<User> findByEmail(String email);
    
    /**
     * 이름으로 사용자 찾기 (부분 일치)
     */
    Optional<User> findByNameContainingIgnoreCase(String name);
    
    /**
     * 전화번호로 사용자 찾기
     */
    Optional<User> findByPhone(String phone);
}
