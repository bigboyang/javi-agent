package com.apmtest.service;

import com.apmtest.entity.User;
import com.apmtest.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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

    // ────────────────────────────────────────────
    // 추가 Java 예외 케이스
    // ────────────────────────────────────────────

    /** ClassCastException */
    public void triggerClassCast() {
        Object obj = Integer.valueOf(42);
        String s = (String) obj;
    }

    /** ArrayIndexOutOfBoundsException */
    public void triggerArrayOob() {
        int[] arr = new int[3];
        int x = arr[99];
    }

    /** NumberFormatException */
    public void triggerNumberFormat() {
        Integer.parseInt("not-a-number");
    }

    /** IllegalArgumentException */
    public void triggerIllegalArgument() {
        throw new IllegalArgumentException("음수 값은 허용되지 않습니다: value=-1");
    }

    /** IllegalStateException */
    public void triggerIllegalState() {
        throw new IllegalStateException("초기화되지 않은 리소스에 접근했습니다");
    }

    /** UnsupportedOperationException */
    public void triggerUnsupportedOp() {
        List<String> fixed = List.of("a", "b", "c");
        fixed.add("d"); // unmodifiable list
    }

    /** ConcurrentModificationException */
    public void triggerConcurrentModification() {
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        for (String item : list) {
            if ("b".equals(item)) {
                list.remove(item);
            }
        }
    }

    // ────────────────────────────────────────────
    // DB 트랜잭션 에러 케이스
    // ────────────────────────────────────────────

    /** NOT NULL 제약 위반 */
    public void triggerNotNullViolation() {
        jdbcTemplate.execute(
            "INSERT INTO users (name, email, phone, created_at, updated_at) " +
            "VALUES (NULL, 'notnull-test@test.com', '010-0000-9999', NOW(), NOW())"
        );
    }

    /** 트랜잭션 롤백 - 부분 insert 후 의도적 롤백 */
    @Transactional
    public void triggerTransactionRollback() {
        jdbcTemplate.execute(
            "INSERT INTO users (name, email, phone, created_at, updated_at) " +
            "VALUES ('롤백유저', 'rollback-test@test.com', '010-0000-8888', NOW(), NOW())"
        );
        throw new RuntimeException("의도적 트랜잭션 롤백 - 이 데이터는 저장되지 않아야 합니다");
    }

    // ────────────────────────────────────────────
    // 외부 HTTP 호출 에러 케이스
    // ────────────────────────────────────────────

    /** 외부 HTTP 연결 타임아웃 */
    public void triggerExternalTimeout() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://10.255.255.1:9999/timeout").openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        conn.connect();
    }

    /** 외부 HTTP 연결 거부 (Connection Refused) */
    public void triggerExternalRefused() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:19999/refused").openConnection();
        conn.setConnectTimeout(1000);
        conn.connect();
    }

    // ────────────────────────────────────────────
    // 메모리/리소스 케이스
    // ────────────────────────────────────────────

    /** 메모리 스파이크 후 해제 (GC 압박 유발) */
    public Map<String, Object> triggerMemorySpike(int mb) {
        int clampedMb = Math.min(mb, 256);
        List<byte[]> sink = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < clampedMb; i++) {
            sink.add(new byte[1024 * 1024]);
        }
        long allocMs = System.currentTimeMillis() - start;
        sink.clear();
        System.gc();

        Map<String, Object> result = new HashMap<>();
        result.put("allocatedMb", clampedMb);
        result.put("allocMs", allocMs);
        result.put("status", "allocated and released");
        return result;
    }

    // ────────────────────────────────────────────
    // 카오스 케이스 (랜덤 에러 혼합)
    // ────────────────────────────────────────────

    /** 랜덤하게 다양한 에러/정상 상황을 발생 */
    public Map<String, Object> chaos() throws Exception {
        int scenario = ThreadLocalRandom.current().nextInt(12);
        switch (scenario) {
            case 0 -> triggerNpe();
            case 1 -> triggerRuntimeException();
            case 2 -> triggerClassCast();
            case 3 -> triggerArrayOob();
            case 4 -> triggerNumberFormat();
            case 5 -> triggerIllegalArgument();
            case 6 -> triggerIllegalState();
            case 7 -> invalidSql();
            case 8 -> tableNotFound();
            case 9 -> { return slowResponse(ThreadLocalRandom.current().nextInt(100, 500)); }
            case 10 -> triggerUnsupportedOp();
            default -> { /* 정상 */ }
        }
        return Map.of("status", "ok", "scenario", scenario);
    }
}
