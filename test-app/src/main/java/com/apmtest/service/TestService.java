package com.apmtest.service;

import com.apmtest.entity.User;
import com.apmtest.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * APM 테스트용 시나리오 서비스.
 *
 * 테스트 케이스:
 *  - DB SQL 에러: 문법 오류, duplicate key, 존재하지 않는 테이블
 *  - 서버 에러: NullPointerException, RuntimeException, StackOverflowError
 *  - 슬로우: 지정 ms 만큼 지연
 *  - 정상: 단순 응답, 다중 쿼리
 */
@Service
public class TestService {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    @Autowired
    public TestService(JdbcTemplate jdbcTemplate, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    // ────────────────────────────────────────────
    // 정상 케이스
    // ────────────────────────────────────────────

    /** 단순 정상 응답 */
    public Map<String, Object> ok() {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TestService.class);
        logger.info("ok() 메서드가 호출되었습니다. traceId={}", org.slf4j.MDC.get("traceId"));
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "정상 응답입니다.");
        return result;
    }

    /**
     * 여러 DB 쿼리 연속 실행.
     * JPA + JdbcTemplate 혼합으로 JDBC-PS / JDBC Statement 모두 계측 확인용.
     */
    public Map<String, Object> multiQuery() {
        // JPA 쿼리 (PreparedStatement)
        List<User> users = userRepository.findAll();

        // JdbcTemplate 쿼리 (Statement)
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);

        Map<String, Object> result = new HashMap<>();
        result.put("userCount", users.size());
        result.put("countFromJdbc", count);
        return result;
    }

    // ────────────────────────────────────────────
    // DB SQL 에러 케이스
    // ────────────────────────────────────────────

    /** SQL 문법 오류 - JdbcTemplate으로 잘못된 SQL 직접 실행 */
    public void invalidSql() {
        jdbcTemplate.execute("SELEKT * FORM users WHERE id = 1");
    }

    /**
     * Duplicate Key 위반 - unique 제약(email)이 걸린 컬럼에 중복 insert.
     * 같은 이메일로 두 번 저장 시도.
     */
    public void duplicateKey() {
        String duplicateEmail = "apm-test-duplicate@test.com";
        jdbcTemplate.execute(
            "INSERT INTO users (name, email, phone, created_at, updated_at) " +
            "VALUES ('테스트유저A', '" + duplicateEmail + "', '010-0000-0001', NOW(), NOW())"
        );
        // 두 번째 insert → unique 제약 위반
        jdbcTemplate.execute(
            "INSERT INTO users (name, email, phone, created_at, updated_at) " +
            "VALUES ('테스트유저B', '" + duplicateEmail + "', '010-0000-0002', NOW(), NOW())"
        );
    }

    /** 존재하지 않는 테이블 조회 */
    public void tableNotFound() {
        jdbcTemplate.queryForList("SELECT * FROM non_existent_table");
    }

    /**
     * 컬럼 길이 초과 - email 컬럼(100자 제한)에 200자 데이터 insert.
     */
    public void columnTooLong() {
        String longEmail = "a".repeat(200) + "@test.com";
        jdbcTemplate.execute(
            "INSERT INTO users (name, email, phone, created_at, updated_at) " +
            "VALUES ('길이초과유저', '" + longEmail + "', '010-0000-0003', NOW(), NOW())"
        );
    }

    // ────────────────────────────────────────────
    // 서버 에러 케이스
    // ────────────────────────────────────────────

    /** NullPointerException */
    public void triggerNpe() {
        String value = null;
        // NPE 발생
        int length = value.length();
    }

    /** RuntimeException */
    public void triggerRuntimeException() {
        throw new RuntimeException("APM 테스트용 RuntimeException 입니다.");
    }

    /** StackOverflowError - 재귀 호출 */
    public void triggerStackOverflow() {
        triggerStackOverflow();
    }

    /** 슬로우 응답 - ms 밀리초 동안 sleep */
    public Map<String, Object> slowResponse(int ms) throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(ms);
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("requestedDelayMs", ms);
        result.put("actualElapsedMs", elapsed);
        return result;
    }
}
