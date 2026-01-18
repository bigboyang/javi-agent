# Java APM Agent

자바 에이전트형 분산추적 APM(Application Performance Monitoring) 시스템입니다.

## 개요

이 프로젝트는 Java Instrumentation API와 ASM 라이브러리를 사용하여 런타임에 클래스 파일을 변환하고, 메서드 실행을 추적하여 성능 모니터링 데이터를 수집합니다.

## 주요 기능

- **바이트코드 조작**: ASM을 사용한 런타임 클래스 변환
- **분산추적**: Trace ID와 Span ID를 통한 요청 추적
- **샘플링**: 성능과 데이터 양의 균형을 위한 샘플링 전략
- **비동기 전송**: HTTP를 통한 추적 데이터 전송
- **설정 가능**: 샘플링 비율, 수집기 URL 등 설정 가능

## 아키텍처

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Application   │    │   Java Agent     │    │   Collector     │
│                 │    │                  │    │                 │
│ - HTTP Request  │───▶│ - ClassTransform │───▶│ - Data Storage  │
│ - DB Query      │    │ - TraceContext   │    │ - Visualization │
│ - Method Call   │    │ - Sampler        │    │                 │
└─────────────────┘    │ - Reporter       │    └─────────────────┘
                       └──────────────────┘
```

## 프로젝트 구조

```
src/main/java/com/apm/
├── agent/           # 에이전트 메인 클래스
├── instrumentation/ # 바이트코드 조작 (ASM)
├── trace/          # 분산추적 로직
├── sampler/        # 샘플링 전략
└── reporter/       # 데이터 전송
```

## 빌드 및 실행

### 1. 프로젝트 빌드

```bash
mvn clean package
```

### 2. JAR 파일 생성 확인

빌드 후 `target/java-apm-agent-1.0.0.jar` 파일이 생성됩니다.

### 3. 에이전트 실행

```bash
java -javaagent:target/java-apm-agent-1.0.0.jar \
     -Dcom.apm.sample.rate=0.5 \
     -Dcom.apm.collector.url=http://localhost:8080/collect \
     -Dcom.apm.service.name=my-service \
     -jar your-application.jar
```

## 설정 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `sample.rate` | 1.0 | 샘플링 비율 (0.0 ~ 1.0) |
| `collector.url` | http://localhost:8080/collect | 수집기 URL |
| `service.name` | unknown-service | 서비스명 |

## 사용 예시

### 1. Spring Boot 애플리케이션

```bash
java -javaagent:java-apm-agent-1.0.0.jar \
     -Dcom.apm.sample.rate=0.3 \
     -Dcom.apm.collector.url=http://apm-collector:8080/collect \
     -Dcom.apm.service.name=user-service \
     -jar user-service.jar
```

### 2. Tomcat 웹 애플리케이션

`catalina.sh`에 추가:

```bash
JAVA_OPTS="$JAVA_OPTS -javaagent:/path/to/java-apm-agent-1.0.0.jar"
JAVA_OPTS="$JAVA_OPTS -Dcom.apm.sample.rate=0.5"
JAVA_OPTS="$JAVA_OPTS -Dcom.apm.collector.url=http://localhost:8080/collect"
JAVA_OPTS="$JAVA_OPTS -Dcom.apm.service.name=web-app"
```

## 추적 대상

### 자동 추적되는 클래스/메서드

- **HTTP Servlet**: `doGet`, `doPost`, `doPut`, `doDelete`, `service`
- **SQL**: `execute`, `executeQuery`, `executeUpdate`
- **Spring**: `DispatcherServlet`
- **HTTP Client**: `HttpClientBuilder`

### 커스텀 추적

추적하고 싶은 메서드에 `@Trace` 어노테이션을 추가하거나, 설정 파일에서 지정할 수 있습니다.

## 데이터 형식

### Span 데이터 구조

```json
{
  "traceId": "abc123def456",
  "spanId": "span789",
  "className": "com.example.UserController",
  "methodName": "getUser",
  "startTime": 1640995200000,
  "endTime": 1640995201000,
  "duration": 1000,
  "tags": {
    "component": "java",
    "class": "com.example.UserController",
    "method": "getUser"
  },
  "attributes": {}
}
```

## 성능 고려사항

- **샘플링**: 기본적으로 100% 샘플링하지만, 프로덕션에서는 10-30% 권장
- **비동기 전송**: 추적 데이터 전송이 애플리케이션 성능에 영향을 주지 않음
- **메모리 사용량**: 활성 스팬은 메모리에 보관되며, 완료 후 자동 정리

## 모니터링

### 메트릭

- 활성 스팬 수
- 큐에 대기 중인 스팬 수
- 전송 성공/실패율

### 로그

에이전트는 다음 정보를 로그로 출력합니다:

- 에이전트 시작/중지
- 클래스 변환 성공/실패
- 데이터 전송 상태

## 개발 환경

- **Java**: 11+
- **Maven**: 3.6+
- **ASM**: 9.5
- **Jackson**: 2.15.2
- **HTTP Client**: 5.2.1

## 라이센스

MIT License

## 기여

버그 리포트, 기능 제안, 풀 리퀘스트를 환영합니다.

## 연락처

프로젝트 관련 문의사항이 있으시면 이슈를 생성해 주세요.
