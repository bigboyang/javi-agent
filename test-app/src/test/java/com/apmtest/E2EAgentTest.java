package com.apmtest;

import com.apmtest.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Javi Agent E2E 테스트.
 *
 * 에이전트 JAR를 -javaagent로 붙인 실제 HTTP 서버 위에서 동작한다.
 * 테스트 항목:
 *   [1] Spring MVC + JDBC: 사용자 CRUD → ControllerMethodAdvice + JdbcPreparedStatementAdvice
 *   [2] 슬로우 응답: 임의 지연 → 슬로우 스팬 기록
 *   [3] 서버 에러: NPE / RuntimeException / StackOverflow → 에러 스팬
 *   [4] DB 에러: invalid-sql / duplicate-key / table-not-found / column-too-long
 *   [5] 정상 다중 쿼리: JPA + JdbcTemplate 복합 호출
 *   [6] HTTP Client: TestRestTemplate → HttpClientAdvice (CLIENT 스팬)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Javi Agent E2E Integration Test")
class E2EAgentTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ─────────────────────────────────────────────────────
    // [1] Spring MVC + JDBC
    // ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("[MVC+JDBC] 사용자 CRUD 흐름 - span 생성 확인")
    void userCrudSpans() {
        User payload = new User("E2E유저", "e2e@test.com", "010-1234-5678");
        ResponseEntity<User> created = restTemplate.postForEntity(url("/api/users"), payload, User.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long id = created.getBody().getId();
        assertThat(id).isNotNull();

        ResponseEntity<User> fetched = restTemplate.getForEntity(url("/api/users/" + id), User.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().getName()).isEqualTo("E2E유저");

        ResponseEntity<User[]> all = restTemplate.getForEntity(url("/api/users"), User[].class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody()).isNotEmpty();

        User update = new User("E2E수정", "e2e-upd@test.com", "010-9999-0000");
        restTemplate.put(url("/api/users/" + id), update);

        restTemplate.delete(url("/api/users/" + id));

        ResponseEntity<User> gone = restTemplate.getForEntity(url("/api/users/" + id), User.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────
    // [2] 슬로우 응답
    // ─────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("[SLOW] 500ms 지연 응답 - 슬로우 span 기록")
    void slowResponseSpan() {
        long start = System.currentTimeMillis();
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/slow?ms=500"), Map.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsed).isGreaterThanOrEqualTo(400);
    }

    // ─────────────────────────────────────────────────────
    // [3] 서버 에러 시나리오
    // ─────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("[ERROR] NPE - 에러 span 기록")
    void npeSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/error/npe"), Map.class);
        assertThat(resp.getStatusCode().is5xxServerError()).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("[ERROR] RuntimeException - 에러 span 기록")
    void runtimeExceptionSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/error/runtime"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsKey("error");
    }

    @Test
    @Order(5)
    @DisplayName("[ERROR] StackOverflow - 에러 span 기록")
    void stackOverflowSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/error/overflow"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─────────────────────────────────────────────────────
    // [4] DB 에러 시나리오
    // ─────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("[DB-ERR] invalid-sql - DB 에러 span 기록")
    void invalidSqlSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/db/invalid-sql"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("error")).isEqualTo("DB_SQL_ERROR");
    }

    @Test
    @Order(7)
    @DisplayName("[DB-ERR] duplicate-key - unique 제약 위반 span 기록")
    void duplicateKeySpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/db/duplicate-key"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("error")).isEqualTo("DB_DUPLICATE_KEY");
    }

    @Test
    @Order(8)
    @DisplayName("[DB-ERR] table-not-found - 테이블 없음 span 기록")
    void tableNotFoundSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/db/table-not-found"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("error")).isEqualTo("DB_TABLE_NOT_FOUND");
    }

    @Test
    @Order(9)
    @DisplayName("[DB-ERR] column-too-long - 컬럼 초과 span 기록")
    void columnTooLongSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/db/column-too-long"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("error")).isEqualTo("DB_COLUMN_TOO_LONG");
    }

    // ─────────────────────────────────────────────────────
    // [5] 정상 다중 쿼리
    // ─────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("[DB-OK] multi-query - JPA+JdbcTemplate 복합 span 기록")
    void multiQuerySpans() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/db/multi-query"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(11)
    @DisplayName("[OK] /api/test/ok - 단순 정상 span 기록")
    void okSpan() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/test/ok"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("ok");
    }

    // ─────────────────────────────────────────────────────
    // [6] HTTP Client span (TestRestTemplate → HttpClientAdvice)
    // ─────────────────────────────────────────────────────

    @Test
    @Order(12)
    @DisplayName("[HTTP-CLIENT] health 엔드포인트 - CLIENT span 기록")
    void httpClientSpan() {
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/api/users/health"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("running");
    }
}
