package com.apmtest.service;

import com.apmtest.entity.User;
import com.apmtest.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * User 관련 비즈니스 로직을 처리하는 서비스
 */
@Service
public class UserService {
    
    private final UserRepository userRepository;
    
    @Autowired
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    
    /**
     * 모든 사용자 조회
     */
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
    
    /**
     * ID로 사용자 조회
     */
    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }
    
    /**
     * 이메일로 사용자 조회
     */
    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }
    
    /**
     * 사용자 생성
     */
    public User createUser(User user) {
        return userRepository.save(user);
    }
    
    /**
     * 사용자 정보 수정
     */
    public User updateUser(Long id, User userDetails) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setName(userDetails.getName());
            user.setEmail(userDetails.getEmail());
            user.setPhone(userDetails.getPhone());
            return userRepository.save(user);
        }
        throw new RuntimeException("사용자를 찾을 수 없습니다: " + id);
    }
    
    /**
     * 사용자 삭제
     */
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }
    
    /**
     * 이름으로 사용자 검색
     */
    public Optional<User> findByNameContaining(String name) {
        return userRepository.findByNameContainingIgnoreCase(name);
    }
}
