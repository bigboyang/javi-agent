package com.apmtest.controller;

import com.apmtest.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * APM 계측 테스트용 컨트롤러.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  카테고리        │  엔드포인트                   │  설명          │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  정상            │  GET /api/test/ok             │  단순 정상응답 │
 * │  정상            │  GET /api/test/db/multi-query │  다중 쿼리     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  DB SQL 에러     │  GET /api/test/db/invalid-sql    │  SQL 문법 오류  │
 * │  DB SQL 에러     │  GET /api/test/db/duplicate-key  │  unique 제약   │
 * │  DB SQL 에러     │  GET /api/test/db/table-not-found│  없는 테이블   │
 * │  DB SQL 에러     │  GET /api/test/db/column-too-long│  컬럼 길이 초과│
 * ├─────────────────────────────────────────────────────────────────┤
 * │  서버 에러       │  GET /api/test/error/npe      │  NullPointerEx │
 * │  서버 에러       │  GET /api/test/error/runtime  │  RuntimeEx     │
 * │  서버 에러       │  GET /api/test/error/overflow │  StackOverflow │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  슬로우          │  GET /api/test/slow?ms=N      │  N ms 지연     │
 * └─────────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "*")
public class TestController {

    private final TestService testService;

    @Autowired
    public TestController(TestService testService) {
        this.testService = testService;
    }

    // ────────────────────────────────────────────
    // 정상 케이스
    // ────────────────────────────────────────────

    /**
     * 단순 정상 응답.
     * GET /api/test/ok
     */
    @GetMapping("/ok")
    public ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok(testService.ok());
    }

    /**
     * 다중 DB 쿼리 (JPA + JdbcTemplate 혼합).
     * JDBC PreparedStatement / Statement 계측을 동시에 확인.
     * GET /api/test/db/multi-query
     */
    @GetMapping("/db/multi-query")
    public ResponseEntity<Map<String, Object>> multiQuery() {
        return ResponseEntity.ok(testService.multiQuery());
    }

    // ────────────────────────────────────────────
    // DB SQL 에러 케이스
    // ────────────────────────────────────────────

    /**
     * SQL 문법 오류 (SELEKT * FORM ...).
     * GET /api/test/db/invalid-sql
     */
    @GetMapping("/db/invalid-sql")
    public ResponseEntity<Map<String, Object>> invalidSql() {
        try {
            testService.invalidSql();
            return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "DB_SQL_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Duplicate Key 위반 (unique 제약 email 컬럼).
     * GET /api/test/db/duplicate-key
     */
    @GetMapping("/db/duplicate-key")
    public ResponseEntity<Map<String, Object>> duplicateKey() {
        try {
            testService.duplicateKey();
            return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "DB_DUPLICATE_KEY",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * 존재하지 않는 테이블 조회.
     * GET /api/test/db/table-not-found
     */
    @GetMapping("/db/table-not-found")
    public ResponseEntity<Map<String, Object>> tableNotFound() {
        try {
            testService.tableNotFound();
            return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "DB_TABLE_NOT_FOUND",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * 컬럼 길이 초과 insert.
     * GET /api/test/db/column-too-long
     */
    @GetMapping("/db/column-too-long")
    public ResponseEntity<Map<String, Object>> columnTooLong() {
        try {
            testService.columnTooLong();
            return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "DB_COLUMN_TOO_LONG",
                "message", e.getMessage()
            ));
        }
    }

    // ────────────────────────────────────────────
    // 서버 에러 케이스
    // ────────────────────────────────────────────

    /**
     * NullPointerException 발생.
     * GET /api/test/error/npe
     */
    @GetMapping("/error/npe")
    public ResponseEntity<Map<String, Object>> npe() {
        testService.triggerNpe();
        return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
    }

    /**
     * RuntimeException 발생.
     * GET /api/test/error/runtime
     */
    @GetMapping("/error/runtime")
    public ResponseEntity<Map<String, Object>> runtimeException() {
        try {
            testService.triggerRuntimeException();
            return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "RUNTIME_EXCEPTION",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * StackOverflowError 발생.
     * GET /api/test/error/overflow
     */
    @GetMapping("/error/overflow")
    public ResponseEntity<Map<String, Object>> stackOverflow() {
        try {
            testService.triggerStackOverflow();
            return ResponseEntity.ok(Map.of("result", "에러가 발생하지 않았습니다."));
        } catch (StackOverflowError e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "STACK_OVERFLOW",
                "message", e.getClass().getSimpleName()
            ));
        }
    }

    // ────────────────────────────────────────────
    // 슬로우 케이스
    // ────────────────────────────────────────────

    /**
     * 지연 응답. ms 파라미터로 지연 시간(밀리초) 지정. 기본 2000ms, 최대 10000ms.
     * GET /api/test/slow?ms=3000
     */
    @GetMapping("/slow")
    public ResponseEntity<Map<String, Object>> slow(
            @RequestParam(defaultValue = "2000") int ms) {
        int clampedMs = Math.min(ms, 10000);
        try {
            return ResponseEntity.ok(testService.slowResponse(clampedMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of(
                "error", "INTERRUPTED",
                "message", e.getMessage()
            ));
        }
    }
}
