package com.apmtest.controller;

import com.apmtest.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ConcurrentModificationException;
import java.util.Map;

/**
 * APM 계측 테스트용 컨트롤러.
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │  카테고리        │  엔드포인트                         │  설명            │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  정상            │  GET /api/test/ok                   │  단순 정상응답   │
 * │  정상            │  GET /api/test/db/multi-query        │  다중 쿼리      │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  DB SQL 에러     │  GET /api/test/db/invalid-sql        │  SQL 문법 오류  │
 * │  DB SQL 에러     │  GET /api/test/db/duplicate-key      │  unique 제약    │
 * │  DB SQL 에러     │  GET /api/test/db/table-not-found    │  없는 테이블    │
 * │  DB SQL 에러     │  GET /api/test/db/column-too-long    │  컬럼 길이 초과 │
 * │  DB SQL 에러     │  GET /api/test/db/not-null           │  NOT NULL 위반  │
 * │  DB SQL 에러     │  GET /api/test/db/tx-rollback        │  트랜잭션 롤백  │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  서버 에러       │  GET /api/test/error/npe             │  NullPointerEx  │
 * │  서버 에러       │  GET /api/test/error/runtime         │  RuntimeEx      │
 * │  서버 에러       │  GET /api/test/error/overflow        │  StackOverflow  │
 * │  서버 에러       │  GET /api/test/error/class-cast      │  ClassCastEx    │
 * │  서버 에러       │  GET /api/test/error/array-oob       │  ArrayOOB       │
 * │  서버 에러       │  GET /api/test/error/number-format   │  NumberFormat   │
 * │  서버 에러       │  GET /api/test/error/illegal-arg     │  IllegalArgEx   │
 * │  서버 에러       │  GET /api/test/error/illegal-state   │  IllegalStateEx │
 * │  서버 에러       │  GET /api/test/error/unsupported     │  UnsupportedOp  │
 * │  서버 에러       │  GET /api/test/error/concurrent-mod  │  ConcurrentMod  │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  HTTP 4xx       │  GET /api/test/http/bad-request      │  400            │
 * │  HTTP 4xx       │  GET /api/test/http/unauthorized      │  401            │
 * │  HTTP 4xx       │  GET /api/test/http/forbidden         │  403            │
 * │  HTTP 4xx       │  GET /api/test/http/not-found         │  404            │
 * │  HTTP 4xx       │  GET /api/test/http/conflict          │  409            │
 * │  HTTP 5xx       │  GET /api/test/http/service-unavail   │  503            │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  외부 호출 에러  │  GET /api/test/external/timeout      │  HTTP 타임아웃  │
 * │  외부 호출 에러  │  GET /api/test/external/refused      │  연결 거부      │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  리소스          │  GET /api/test/resource/memory?mb=N  │  메모리 스파이크│
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  카오스          │  GET /api/test/chaos                 │  랜덤 에러 혼합 │
 * │  슬로우          │  GET /api/test/slow?ms=N             │  N ms 지연      │
 * └───────────────────────────────────────────────────────────────────────┘
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

    // ────────────────────────────────────────────
    // 추가 Java 예외 케이스 (전파하여 agent가 에러 로그 캡처)
    // ────────────────────────────────────────────

    /** GET /api/test/error/class-cast */
    @GetMapping("/error/class-cast")
    public ResponseEntity<Map<String, Object>> classCast() {
        testService.triggerClassCast();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    /** GET /api/test/error/array-oob */
    @GetMapping("/error/array-oob")
    public ResponseEntity<Map<String, Object>> arrayOob() {
        testService.triggerArrayOob();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    /** GET /api/test/error/number-format */
    @GetMapping("/error/number-format")
    public ResponseEntity<Map<String, Object>> numberFormat() {
        testService.triggerNumberFormat();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    /** GET /api/test/error/illegal-arg */
    @GetMapping("/error/illegal-arg")
    public ResponseEntity<Map<String, Object>> illegalArg() {
        testService.triggerIllegalArgument();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    /** GET /api/test/error/illegal-state */
    @GetMapping("/error/illegal-state")
    public ResponseEntity<Map<String, Object>> illegalState() {
        testService.triggerIllegalState();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    /** GET /api/test/error/unsupported */
    @GetMapping("/error/unsupported")
    public ResponseEntity<Map<String, Object>> unsupportedOp() {
        testService.triggerUnsupportedOp();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    /** GET /api/test/error/concurrent-mod */
    @GetMapping("/error/concurrent-mod")
    public ResponseEntity<Map<String, Object>> concurrentMod() {
        testService.triggerConcurrentModification();
        return ResponseEntity.ok(Map.of("result", "no error"));
    }

    // ────────────────────────────────────────────
    // DB 트랜잭션 에러 케이스
    // ────────────────────────────────────────────

    /** NOT NULL 제약 위반. GET /api/test/db/not-null */
    @GetMapping("/db/not-null")
    public ResponseEntity<Map<String, Object>> notNull() {
        try {
            testService.triggerNotNullViolation();
            return ResponseEntity.ok(Map.of("result", "no error"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "DB_NOT_NULL", "message", e.getMessage()));
        }
    }

    /** 트랜잭션 롤백. GET /api/test/db/tx-rollback */
    @GetMapping("/db/tx-rollback")
    public ResponseEntity<Map<String, Object>> txRollback() {
        try {
            testService.triggerTransactionRollback();
            return ResponseEntity.ok(Map.of("result", "no error"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "TX_ROLLBACK", "message", e.getMessage()));
        }
    }

    // ────────────────────────────────────────────
    // HTTP 상태 코드 케이스
    // ────────────────────────────────────────────

    /** 400 Bad Request. GET /api/test/http/bad-request */
    @GetMapping("/http/bad-request")
    public ResponseEntity<Map<String, Object>> httpBadRequest() {
        return ResponseEntity.status(400).body(Map.of("error", "BAD_REQUEST", "message", "필수 파라미터가 누락되었습니다."));
    }

    /** 401 Unauthorized. GET /api/test/http/unauthorized */
    @GetMapping("/http/unauthorized")
    public ResponseEntity<Map<String, Object>> httpUnauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "UNAUTHORIZED", "message", "인증이 필요합니다."));
    }

    /** 403 Forbidden. GET /api/test/http/forbidden */
    @GetMapping("/http/forbidden")
    public ResponseEntity<Map<String, Object>> httpForbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "FORBIDDEN", "message", "접근 권한이 없습니다."));
    }

    /** 404 Not Found. GET /api/test/http/not-found */
    @GetMapping("/http/not-found")
    public ResponseEntity<Map<String, Object>> httpNotFound() {
        return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND", "message", "요청한 리소스를 찾을 수 없습니다."));
    }

    /** 409 Conflict. GET /api/test/http/conflict */
    @GetMapping("/http/conflict")
    public ResponseEntity<Map<String, Object>> httpConflict() {
        return ResponseEntity.status(409).body(Map.of("error", "CONFLICT", "message", "리소스 충돌이 발생했습니다."));
    }

    /** 503 Service Unavailable. GET /api/test/http/service-unavail */
    @GetMapping("/http/service-unavail")
    public ResponseEntity<Map<String, Object>> httpServiceUnavail() {
        return ResponseEntity.status(503).body(Map.of("error", "SERVICE_UNAVAILABLE", "message", "서비스를 일시적으로 사용할 수 없습니다."));
    }

    // ────────────────────────────────────────────
    // 외부 HTTP 호출 에러 케이스
    // ────────────────────────────────────────────

    /** 외부 연결 타임아웃. GET /api/test/external/timeout */
    @GetMapping("/external/timeout")
    public ResponseEntity<Map<String, Object>> externalTimeout() {
        try {
            testService.triggerExternalTimeout();
            return ResponseEntity.ok(Map.of("result", "no error"));
        } catch (Exception e) {
            return ResponseEntity.status(504).body(Map.of("error", "EXTERNAL_TIMEOUT", "message", e.getMessage()));
        }
    }

    /** 외부 연결 거부. GET /api/test/external/refused */
    @GetMapping("/external/refused")
    public ResponseEntity<Map<String, Object>> externalRefused() {
        try {
            testService.triggerExternalRefused();
            return ResponseEntity.ok(Map.of("result", "no error"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "CONNECTION_REFUSED", "message", e.getMessage()));
        }
    }

    // ────────────────────────────────────────────
    // 리소스 케이스
    // ────────────────────────────────────────────

    /** 메모리 스파이크 후 해제. GET /api/test/resource/memory?mb=50 */
    @GetMapping("/resource/memory")
    public ResponseEntity<Map<String, Object>> memorySpike(@RequestParam(defaultValue = "50") int mb) {
        return ResponseEntity.ok(testService.triggerMemorySpike(mb));
    }

    // ────────────────────────────────────────────
    // 카오스 케이스
    // ────────────────────────────────────────────

    /** 랜덤 에러 혼합. GET /api/test/chaos */
    @GetMapping("/chaos")
    public ResponseEntity<Map<String, Object>> chaos() {
        try {
            Map<String, Object> result = testService.chaos();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
        }
    }
}
