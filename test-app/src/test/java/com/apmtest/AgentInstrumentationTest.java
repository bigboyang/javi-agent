package com.apmtest;

import com.apmtest.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에이전트 계측 통합 테스트.
 *
 * RANDOM_PORT 환경에서 실제 HTTP 서버를 구동하여
 * Spring MVC / JDBC PreparedStatement / HTTP Client (RestTemplate) 세 가지
 * ByteBuddy 계측이 동시에 동작함을 검증한다.
 *
 *  - Spring MVC  : TestRestTemplate → 실제 HTTP 연결 → ControllerMethodAdvice 발동
 *  - JDBC        : 컨트롤러가 JPA를 통해 DB 쿼리 → JdbcPreparedStatementAdvice 발동
 *  - HTTP Client : TestRestTemplate 내부 RestTemplate.doExecute() → HttpClientAdvice 발동
 *
 * 스팬 내용은 콘솔에 "[agent] export span ..." 형태로 출력된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentInstrumentationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("Spring MVC + JDBC: 사용자 생성 및 조회 시 스팬이 생성된다")
    void springMvcAndJdbcSpansAreGenerated() {
        // POST /api/users  →  ControllerMethodAdvice + JdbcPreparedStatementAdvice (INSERT)
        User payload = new User("스팬테스트", "span-test@example.com", "010-0000-0001");
        ResponseEntity<User> created = restTemplate.postForEntity(url("/api/users"), payload, User.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long id = created.getBody().getId();

        // GET /api/users/{id}  →  ControllerMethodAdvice + JdbcPreparedStatementAdvice (SELECT)
        ResponseEntity<User> fetched = restTemplate.getForEntity(url("/api/users/" + id), User.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().getName()).isEqualTo("스팬테스트");

        // GET /api/users  →  전체 목록 조회
        ResponseEntity<User[]> all = restTemplate.getForEntity(url("/api/users"), User[].class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody()).isNotEmpty();
    }

    @Test
    @DisplayName("HTTP Client: TestRestTemplate(RestTemplate) 호출 시 CLIENT 스팬이 생성된다")
    void httpClientSpanIsGenerated() {
        // TestRestTemplate은 내부적으로 RestTemplate.doExecute()를 사용한다.
        // HttpClientAdvice가 해당 메서드를 인터셉트하여 CLIENT 스팬을 생성한다.
        ResponseEntity<String> health = restTemplate.getForEntity(url("/api/users/health"), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("running");
    }

    @Test
    @DisplayName("CRUD 전체 흐름: 생성/수정/삭제 시 스팬이 각각 생성된다")
    void crudFlowGeneratesSpans() {
        // 생성
        User payload = new User("CRUD테스트", "crud-span@example.com", "010-9999-8888");
        ResponseEntity<User> created = restTemplate.postForEntity(url("/api/users"), payload, User.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = created.getBody().getId();

        // 수정
        User update = new User("CRUD수정", "crud-updated@example.com", "010-7777-6666");
        restTemplate.put(url("/api/users/" + id), update);

        // 삭제
        restTemplate.delete(url("/api/users/" + id));

        // 삭제 후 404 확인
        ResponseEntity<User> gone = restTemplate.getForEntity(url("/api/users/" + id), User.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
