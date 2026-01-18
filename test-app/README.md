# Spring Boot Test Application

JPA와 H2 데이터베이스를 사용하는 간단한 Spring Boot API 애플리케이션입니다.

## 🚀 기능

- **사용자 관리**: CRUD 작업 (생성, 조회, 수정, 삭제)
- **JPA 엔티티**: User 엔티티와 관계형 데이터베이스 매핑
- **REST API**: HTTP 메서드를 사용한 API 엔드포인트
- **H2 데이터베이스**: 인메모리 데이터베이스 (개발/테스트용)

## 🏗️ 프로젝트 구조

```
test-app/
├── src/main/java/com/test/test/
│   ├── TestApplication.java      # 메인 애플리케이션
│   ├── entity/
│   │   └── User.java            # User JPA 엔티티
│   ├── repository/
│   │   └── UserRepository.java  # JPA Repository
│   ├── service/
│   │   └── UserService.java     # 비즈니스 로직
│   ├── controller/
│   │   └── UserController.java  # REST API 컨트롤러
│   └── config/
│       └── DataInitializer.java # 초기 데이터 생성
├── src/main/resources/
│   └── application.properties    # 설정 파일
└── pom.xml                      # Maven 의존성
```

## 🛠️ 기술 스택

- **Spring Boot 3.2.0**
- **Spring Data JPA**
- **H2 Database (인메모리)**
- **Java 17**
- **Maven**

## 📋 API 엔드포인트

### 사용자 관리 API

| 메서드 | 엔드포인트 | 설명 |
|--------|------------|------|
| `GET` | `/api/users` | 모든 사용자 조회 |
| `GET` | `/api/users/{id}` | ID로 사용자 조회 |
| `GET` | `/api/users/email/{email}` | 이메일로 사용자 조회 |
| `GET` | `/api/users/search?name={name}` | 이름으로 사용자 검색 |
| `POST` | `/api/users` | 새 사용자 생성 |
| `PUT` | `/api/users/{id}` | 사용자 정보 수정 |
| `DELETE` | `/api/users/{id}` | 사용자 삭제 |
| `GET` | `/api/users/health` | API 상태 확인 |

## 🚀 실행 방법

### 1. 애플리케이션 빌드 및 실행

```bash
# 프로젝트 폴더로 이동
cd test-app

# Maven으로 빌드 및 실행
mvn spring-boot:run
```

### 2. JAR 파일로 실행

```bash
# 빌드
mvn clean package

# 실행
java -jar target/test-0.0.1-SNAPSHOT.jar
```

## 🌐 접속 정보

- **애플리케이션**: http://localhost:8080
- **H2 콘솔**: http://localhost:8080/h2-console
- **API 문서**: http://localhost:8080/api/users

### H2 데이터베이스 접속 정보
- **JDBC URL**: `jdbc:h2:mem:testdb`
- **사용자명**: `sa`
- **비밀번호**: (비어있음)

## 📊 초기 데이터

애플리케이션 시작 시 자동으로 생성되는 사용자:

1. **김철수** - kim@example.com - 010-1234-5678
2. **이영희** - lee@example.com - 010-2345-6789
3. **박민수** - park@example.com - 010-3456-7890

## 🧪 API 테스트

### 사용자 생성 예시

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "홍길동",
    "email": "hong@example.com",
    "phone": "010-9999-8888"
  }'
```

### 모든 사용자 조회

```bash
curl http://localhost:8080/api/users
```

### 특정 사용자 조회

```bash
curl http://localhost:8080/api/users/1
```

## 🔍 로그 확인

애플리케이션 실행 시 다음 로그를 확인할 수 있습니다:

- JPA SQL 쿼리 로그
- Hibernate 바인딩 로그
- 초기 데이터 생성 로그
- API 요청/응답 로그

## 🚨 문제 해결

### 포트 충돌 시
```properties
# application.properties에서 포트 변경
server.port=8081
```

### 데이터베이스 초기화
```properties
# application.properties에서 테이블 재생성
spring.jpa.hibernate.ddl-auto=create-drop
```

## 📝 다음 단계

이 기본 애플리케이션을 기반으로:

1. **Agent 테스트**: Java Agent와 함께 실행하여 추적 기능 테스트
2. **추가 기능**: 파일 업로드, 인증, 권한 등
3. **데이터베이스 변경**: MySQL, PostgreSQL 등으로 전환
4. **프론트엔드**: React, Vue.js 등과 연동

