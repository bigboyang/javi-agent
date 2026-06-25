# javi-agent 아키텍처 가이드

> 이 문서는 PR 마다 자동 생성/갱신됩니다.

이 문서는 javi-agent에 처음 합류한 개발자가 코드를 빠르게 파악할 수 있도록 풀어서 쓴 온보딩 문서입니다. AI 에이전트용 간결 문서인 `CLAUDE.md`와 달리, 여기서는 "왜 이렇게 만들었는지"까지 설명합니다.

## 1. 이 프로젝트는 무엇인가

javi-agent는 **Java 애플리케이션에 `-javaagent`로 붙는 자체 구현 APM(Application Performance Monitoring) 에이전트**입니다. `agent/README.md`에 적힌 초기 설계 메모를 보면, OpenTelemetry가 정의한 Trace/Metric/Log 모델과 OTLP 프로토콜을 참고하되, SDK 자체를 외부 OpenTelemetry 라이브러리 없이 직접 구현하는 것을 목표로 시작된 프로젝트입니다.

핵심 동작은 다음과 같습니다.

- 대상 JVM에 `java -javaagent:javi-1.0.0.jar -jar app.jar`로 부착되면, JVM이 메인 클래스 실행 전에 `com.agent.SimpleAgent#premain`을 호출합니다.
- premain은 [ByteBuddy](https://bytebuddy.net/)를 이용해 HTTP, JDBC, 메시지 큐, gRPC, 캐시 등 흔히 쓰는 라이브러리의 메서드에 바이트코드 수준으로 끼어들어(advice), 호출이 일어날 때마다 span을 만듭니다.
- 만들어진 span/log/metric은 에이전트가 직접 구현한 SDK(`JaviSdk`)가 모으고 배치 처리한 뒤, OTLP/HTTP Protobuf 포맷으로 별도 collector(이 레포 밖에 있는 `javi-collector`, Go로 작성된 것으로 보임)에 전송합니다.
- collector 쪽 endpoint, 샘플링 비율 등은 환경변수/시스템 프로퍼티로 부팅 시 정해지고, 이후에도 별도의 config-server에서 polling 방식으로 동적으로 바꿀 수 있습니다.

즉 이 레포 하나만으로는 "관측 데이터를 보내는 쪽"만 구현되어 있고, 수신/저장/조회하는 collector나 config-server는 다른 레포로 분리되어 있는 것으로 보입니다(이 레포 안에는 없습니다 — `e2e-test.sh`, `start-with-remote-config.sh`가 그 존재를 전제로 동작합니다).

## 2. 전체 아키텍처

레포는 크게 세 부분으로 나뉩니다.

```
javi-agent/
├── agent/        에이전트 본체 (Maven, javaagent jar로 빌드됨)
├── test-app/     에이전트를 붙여서 검증하는 Spring Boot 샘플 앱
└── *.sh          로컬 실행/E2E 테스트용 스크립트
```

`agent/`는 다시 신호(Trace/Log/Metric)별 SDK 패키지와, 그 신호를 자동으로 만들어내는 instrumentation 패키지로 나뉩니다.

```
agent/src/main/java/com/agent/
├── SimpleAgent.java        premain 진입점 — 플러그인 설치, 수집기 기동, shutdown hook 등록
├── instrumentation/        ByteBuddy 기반 자동 계측
│   ├── AgentRuntime.java   TracerProvider/LoggerProvider/MeterProvider 초기화·보관
│   ├── InstrumentationPlugin.java   계측 플러그인 인터페이스
│   ├── plugin/             각 라이브러리별 플러그인 구현 (HTTP, JDBC, Kafka, gRPC, Redis ...)
│   └── *Advice.java        ByteBuddy @Advice 메서드 — 실제로 끼어드는 코드
├── trace/                  Tracer, SdkTracerProvider, SpanProcessor, SpanExporter
├── span/                   Span, SpanContext, SdkSpan(구현체), Scope, Context
├── sampler/                샘플링 정책 (AlwaysOn/Off, 비율 기반, 부모 기반, 적응형)
├── propagation/            W3C traceparent/baggage 인코딩·디코딩
├── concurrent/             스레드 풀/비동기 작업으로 trace context 전파
├── metric/                 Meter, Counter/Gauge/Histogram, JVM/K8s/Tomcat/Hikari 수집기
├── logs/                   애플리케이션 로그(Logback/Log4j2) 수집과 MDC 연동
├── config/                 부팅 설정(AgentConfig) + 원격 설정 폴링(RemoteConfig)
├── profiling/              JFR/스레드 샘플링 기반 프로파일링, pprof 인코딩
└── common/                 신호 공통: JaviSdk, OTLP 전송기, protobuf 인코더, sanitizer, id 생성기
```

ASCII로 컴포넌트 관계를 그리면 대략 이렇습니다.

```
                         ┌────────────────────────┐
   대상 애플리케이션      │  -javaagent:javi.jar     │
   (test-app 등)  ───────►  SimpleAgent#premain      │
                         └───────────┬──────────────┘
                                     │ ServiceLoader로 SPI 플러그인 적재
                                     ▼
                  ┌───────────────────────────────────────┐
                  │ instrumentation/plugin/*               │
                  │ (Servlet, SpringMvc, JDBC, Kafka,       │
                  │  Redis, gRPC, ExecutorService ...)      │
                  └───────────────┬─────────────────────────┘
                                  │ ByteBuddy @Advice로 메서드 가로채기
                                  ▼
                  ┌───────────────────────────────────────┐
                  │ AgentRuntime (TracerProvider 등 보유)    │
                  │  → Tracer.spanBuilder().startSpan()      │
                  └───────────────┬─────────────────────────┘
                                  ▼
                  ┌───────────────────────────────────────┐
                  │ Sampler가 기록 여부 결정                  │
                  │ (AlwaysOn/Off, 비율, 부모기반, Adaptive) │
                  └───────────────┬─────────────────────────┘
                                  ▼
                  ┌───────────────────────────────────────┐
                  │ SpanProcessor 체인 → BatchSpanProcessor │
                  │ (큐에 쌓고 배치/타임아웃 단위로 flush)     │
                  └───────────────┬─────────────────────────┘
                                  ▼
                  ┌───────────────────────────────────────┐
                  │ OtlpHttpSpanExporter                    │
                  │  → ProtoEncoder로 protobuf 바이너리 인코딩│
                  └───────────────┬─────────────────────────┘
                                  ▼
                  ┌───────────────────────────────────────┐
                  │ OtlpHttpProtobufSender                  │
                  │  HTTP POST + 재시도 + Circuit Breaker    │
                  └───────────────┬─────────────────────────┘
                                  ▼
                          javi-collector (별도 레포, Go)
```

Log/Metric도 같은 모양(수집 → BatchProcessor → Exporter → OtlpHttpProtobufSender)을 따르되, 각자의 패키지(`logs/`, `metric/`)에 동일한 패턴이 반복돼 있습니다.

## 3. 데이터 흐름 — 요청 하나가 어떻게 span이 되어 나가는가

예를 들어 test-app의 `UserController`가 HTTP 요청을 받는 상황을 따라가 보면:

1. **계측 진입**: `HttpServletAdvice`가 `HttpServlet#service()` 호출을 가로채, `AgentRuntime`이 들고 있는 `Tracer`로 SERVER 종류의 span을 시작합니다. 들어온 요청 헤더에서 W3C `traceparent`를 읽어 부모 컨텍스트가 있으면 이어붙입니다(`TraceContextPropagator`).
2. **컨텍스트 활성화**: 만들어진 span을 `Span#makeCurrent()`로 현재 스레드의 컨텍스트(ThreadLocal)에 등록합니다(`Scope`). 이 컨텍스트는 같은 요청 처리 중 호출되는 JDBC, Kafka, gRPC 등의 자식 span이 부모를 찾을 때 쓰입니다.
3. **자식 span 생성**: 요청 처리 중 JDBC 쿼리를 실행하면 `JdbcPreparedStatementAdvice`/`JdbcStatementAdvice`가 또 다른 advice로 끼어들어 DB span을 만들고, SQL은 `SqlSanitizer`로 리터럴을 마스킹한 뒤 속성으로 기록합니다.
4. **요청 종료**: 컨트롤러 메서드가 끝나면 advice의 `finally` 구간에서 `span.end()`가 호출되고, 그 시점에 `Sampler`가 이 span을 실제로 내보낼지(`RECORD_AND_SAMPLE`) 버릴지(`DROP`) 결정합니다.
5. **배치 처리**: 샘플링을 통과한 span은 `SpanProcessor` 체인을 거쳐 `BatchSpanProcessor`의 큐에 쌓입니다. 큐가 일정 크기에 도달하거나 타임아웃이 지나면 모아서 flush합니다.
6. **인코딩과 전송**: `OtlpHttpSpanExporter`가 모인 span들을 `ProtoEncoder`로 OTLP protobuf 바이너리로 직접 인코딩하고, `OtlpHttpProtobufSender`가 HTTP로 collector에 전송합니다. 전송이 실패하면 재시도하고, 연속 실패가 누적되면 Circuit Breaker가 일시적으로 전송을 끊습니다(§6.3 참고).
7. **JVM 종료 시 flush**: `SimpleAgent`가 등록한 shutdown hook이 종료 직전에 남은 span/log/metric을 강제로 flush합니다(`AgentRuntime.provider().forceFlush()`).

Log와 Metric도 트리거만 다를 뿐 같은 구조(수집 → 배치 → OTLP 전송)를 따릅니다.

- **Log**: `AppLogCollector`가 Logback/Log4j2 appender에 끼어들어 애플리케이션이 남기는 로그를 그대로 수집하고, 현재 활성 span의 traceId/spanId를 SLF4J MDC에 주입해 로그와 trace를 연결합니다.
- **Metric**: `MetricsCollectorScheduler`가 주기적으로 JVM(GC, heap, thread), K8s(pod/node 메타데이터), Tomcat, HikariCP 커넥션 풀 등의 수집기를 실행해 메트릭을 모읍니다.

## 4. 디렉터리/모듈별 책임

### `agent/src/main/java/com/agent/SimpleAgent.java`
javaagent의 `premain` 진입점. 하는 일은 다음 순서로 명확히 나뉘어 있습니다: `AgentRuntime.provider()`로 SDK 초기화 → ByteBuddy `AgentBuilder` 두 개(일반/bootstrap classloader용) 준비 → SPI(`ServiceLoader`)로 찾은 `InstrumentationPlugin`을 `order()` 순으로 설치 → 로그/메트릭/프로파일링 수집기 기동 → JVM 종료 시 flush하는 shutdown hook 등록.

### `instrumentation/`
가장 코드가 많은 패키지입니다. **플러그인(`plugin/` 하위, 약 29개 클래스)**은 "어떤 클래스의 어떤 메서드를 가로챌지"를 ByteBuddy `AgentBuilder`에 등록하는 선언부이고, **advice(`*Advice.java`, 패키지 루트)**는 실제로 그 메서드 앞뒤에서 실행되는 코드입니다. 둘을 분리해둔 이유는 advice 코드가 대상 애플리케이션의 클래스로더에 인라인되어 실행되기 때문에, "무엇을 계측할지 결정하는 코드"와 "대상 프로세스 안에서 실제로 도는 코드"의 책임을 나누는 것이 ByteBuddy의 일반적인 패턴이기 때문입니다.

플러그인은 HTTP 서버(Servlet, Spring MVC, WebFlux), HTTP 클라이언트(RestTemplate, WebClient, Apache HttpClient, OkHttp, Feign, Java 11 HttpClient), JDBC, 메시지 큐(Kafka, RabbitMQ), 캐시/DB(Redis/Lettuce, Jedis, MongoDB, Elasticsearch), gRPC, 비동기/스레드(ExecutorService, CompletableFuture, Spring @Async, 스케줄 작업), 로깅(Logback, Log4j2) 영역을 각각 다룹니다. 새 라이브러리를 계측하려면 이 디렉터리에 플러그인 + advice 한 쌍을 추가하고 `META-INF/services/com.agent.instrumentation.InstrumentationPlugin`에 등록하는 식으로 확장하면 됩니다.

### `trace/`, `span/`
OpenTelemetry의 Trace API/SDK 분리 모델을 따라 합니다. `trace/`는 `Tracer`/`TracerProvider`처럼 "span을 만드는 공개 인터페이스"와 그 SDK 구현(`SdkTracer`, `SdkTracerProvider`), 그리고 만들어진 span을 내보내는 `SpanProcessor`/`SpanExporter`를 둡니다. `span/`은 span 자체의 데이터 모델(`Span`, `SpanContext`, `SdkSpan`)과 컨텍스트 전파 단위(`Scope`, `Context`)를 둡니다. 둘로 나눈 이유는 "span을 어떻게 만들고 내보내는가(트레이서 책임)"와 "span이 무엇인가(span 책임)"를 구분해, 추후 다른 신호도 비슷한 패턴(LoggerProvider, MeterProvider)으로 일관되게 확장할 수 있게 한 것으로 보입니다.

### `sampler/`
어떤 span을 실제로 보낼지 결정하는 정책 모음입니다. 단순한 `AlwaysOnSampler`/`AlwaysOffSampler`/`TraceIdRatioBasedSampler`/`ParentBasedSampler` 외에 `AdaptiveSampler`가 있는데, 이건 목표 초당 스팬 수(target SPS)를 유지하도록 샘플링 비율을 주기적으로 자동 조정하는 정책입니다. `AgentConfig`의 `criticalUrls`에 해당하는 요청은 이 비율 계산과 무관하게 항상 샘플링됩니다.

### `propagation/`
서비스 간 trace 컨텍스트를 HTTP 헤더 등으로 주고받기 위한 W3C 표준 포맷(`traceparent`, `baggage`) 인코더/디코더입니다. HTTP 클라이언트 advice가 나가는 요청에 헤더를 심고, HTTP 서버 advice가 들어오는 요청에서 헤더를 읽는 데 씁니다.

### `concurrent/`
trace 컨텍스트는 기본적으로 ThreadLocal에 저장되기 때문에, 스레드를 넘나드는 비동기 작업에서는 컨텍스트가 끊깁니다. 이 패키지는 `ContextSnapshot`으로 현재 컨텍스트를 캡처해 `ContextPropagatingRunnable`/`Callable`로 감싸는 방식으로, `ExecutorServiceAdvice`/`CompletableFutureAdvice` 등이 비동기 작업에도 컨텍스트를 이어주게 합니다.

### `metric/`, `logs/`
각각 메트릭과 로그 신호에 대해 `trace/`와 같은 모양(Provider → BatchProcessor → Exporter)을 반복합니다. `metric/`에는 JVM/K8s/Tomcat/HikariCP처럼 애플리케이션이 직접 만들지 않아도 에이전트가 스스로 주기적으로 긁어오는 수집기들이 따로 있습니다. `logs/`에는 애플리케이션이 Logback/Log4j2로 남기는 로그를 그대로 가져오는 `AppLogCollector`와, 에이전트 자신의 동작을 기록하는 `AgentLogger`가 별도로 존재합니다(에이전트 자체 로그와 사용자 앱 로그를 섞지 않기 위함으로 보입니다).

### `config/`
`AgentConfig`는 JVM 부팅 시 환경변수/시스템 프로퍼티에서 한 번 읽어 불변으로 고정되는 설정이고(엔드포인트, 서비스명, 샘플링 비율 등), `RemoteConfig`/`RemoteConfigPoller`/`RemoteConfigHolder`는 별도 config-server를 주기적으로 polling해서 *런타임에* 바꿀 수 있는 설정을 다룹니다. 부팅 설정과 런타임 동적 설정을 분리한 것은, JVM을 재시작하지 않고도 샘플링 정책이나 응급 차단(`emergencyOff`) 같은 걸 즉시 바꿀 수 있게 하려는 의도로 보입니다.

### `profiling/`
JFR(Java Flight Recorder) 또는 스레드 샘플링 기반으로 CPU 프로파일을 주기적으로 떠서 pprof 포맷으로 인코딩해 내보내는 부가 기능입니다. trace/log/metric과는 독립적으로 `ProfilingScheduler`가 따로 기동/종료됩니다.

### `common/`
신호 종류와 무관하게 공유되는 부품들입니다.
- `JaviSdk` — TracerProvider/LoggerProvider/MeterProvider를 한 곳에서 들고 있는 싱글톤. 다른 코드가 "지금 활성화된 SDK가 뭔지" 알아야 할 때 여길 거칩니다.
- `OtlpHttpProtobufSender` — 실제 네트워크 전송을 담당. 재시도와 Circuit Breaker가 여기 있습니다.
- `ProtoEncoder` — 외부 protobuf 라이브러리 없이 OTLP 메시지를 바이너리로 직접 인코딩합니다.
- `utils/HeaderSanitizer`, `utils/SqlSanitizer`, `utils/UrlSanitizer` — span에 기록되기 전에 민감 정보를 제거/마스킹합니다(§6 참고).
- `utils/generator/IdGenerator` — trace/span ID 생성.

## 5. 로컬 개발 시작법

### 필요 도구
- JDK 11 이상 (agent의 `maven.compiler.source/target`은 11, test-app은 17 기준 — `agent/pom.xml`, `test-app/pom.xml` 참고)
- Maven
- (E2E 테스트를 하려면) Go, 그리고 `javi-collector` 레포를 별도로 clone해 둘 것 — 이 레포 안에는 collector 코드가 없습니다.

### 가장 빠른 시작
```bash
cd agent && mvn clean package -DskipTests      # javi-1.0.0.jar 빌드
cd ../test-app && mvn clean package -DskipTests # 테스트 앱 빌드
java -javaagent:agent/target/javi-1.0.0.jar -jar test-app/target/test-0.0.1-SNAPSHOT.jar
```

**주의**: 루트의 `start.sh`/`start-with-remote-config.sh`는 작성자의 로컬 절대 경로(`/Users/kkc/APM/...`)가 하드코딩되어 있어 그대로 실행하면 다른 환경에서는 동작하지 않습니다. 참고용으로 보고 직접 경로를 바꿔서 쓰거나, 위 명령을 상대 경로로 직접 실행하는 편이 안전합니다.

### 원격 설정 연동
`start-with-remote-config.sh`의 주석에 따르면, 별도의 `javi-config-server`(포트 18888)를 먼저 띄워야 합니다.
```bash
curl http://localhost:18888/api/config
curl -X PATCH "http://localhost:18888/api/config/emergencyOff?value=true"
curl -X PATCH "http://localhost:18888/api/config/headSampleRate?value=0.5"
```
에이전트는 `javi.remote.config.url`/`javi.remote.config.poll.interval.sec` 시스템 프로퍼티로 이 서버를 가리키게 하면, `RemoteConfigPoller`가 주기적으로 폴링해 설정을 갱신합니다.

### 자주 쓰는 설정값 (환경변수 또는 `-D` 시스템 프로퍼티)
`agent/src/main/java/com/agent/config/AgentConfig.java`에서 확인된 항목입니다.

| 환경변수 | 시스템 프로퍼티 | 기본값 | 의미 |
|---|---|---|---|
| `JAVI_COLLECTOR_ENDPOINT` | `javi.collector.endpoint` | `http://localhost:4318` | OTLP collector 주소 |
| `JAVI_SERVICE_NAME` | `javi.service.name` | `javi-service` | 서비스 이름 |
| `JAVI_SAMPLE_RATE` | `javi.sample.rate` | `1.0` | span 샘플링 비율(0~1) |
| `JAVI_SLOW_THRESHOLD_MS` | `javi.slow.threshold.ms` | `500` | slow span 판정 기준(ms) |
| `JAVI_CRITICAL_URLS` | `javi.critical.urls` | (없음) | 항상 샘플링할 URL(쉼표로 구분) |
| `JAVI_CLUSTER_MIN_SAMPLES` | `javi.cluster.min.samples` | `5` | 적응형 샘플러 최소 관측 수 |
| `JAVI_SAMPLING_TARGET_SPS` | `javi.sampling.target.sps` | `0` | 적응형 샘플러 목표 초당 스팬 수 |

### E2E 테스트
- `./e2e-test.sh`: 로컬에서 `javi-collector`(Go, 별도 레포여야 함)를 빌드/기동하고, agent를 붙인 test-app에 CRUD/에러/슬로우 요청을 보낸 뒤 collector가 실제로 trace/metric을 받았는지 확인합니다.
- `./e2e-k8s-test.sh`: 같은 검증을 쿠버네티스에 떠 있는 test-app/collector/forecast 파드를 대상으로, `kubectl port-forward`로 연결해 수행합니다. `apm` 네임스페이스와 사전에 떠 있는 파드를 전제로 합니다.

두 스크립트 모두 이 레포 밖의 `javi-collector`(그리고 k8s 테스트의 경우 forecast 서비스)가 준비되어 있어야 끝까지 통과합니다.

## 6. 알아두면 좋은 함정·주의사항·설계상 트레이드오프

### 6.1 OpenTelemetry SDK를 직접 쓰지 않고 자체 구현했다
이 레포는 OpenTelemetry의 *모델*(Span/Tracer/Processor/Exporter 분리, W3C traceparent)을 참고하지만, 실제 SDK 코드와 protobuf 처리(`ProtoEncoder`)는 외부 라이브러리 없이 직접 작성되어 있습니다(`agent/pom.xml`의 의존성에 `io.opentelemetry.*`가 없고, ByteBuddy와 각 instrumented 라이브러리의 `provided` 의존성만 있습니다). 장점은 의존성이 가볍고 세밀한 최적화가 가능하다는 것이고, 트레이드오프는 OTLP 스펙이 바뀌면 직접 따라가야 하고, 공식 OpenTelemetry 생태계(자동 계측 라이브러리, 표준 익스포터 등)를 그대로 가져다 쓸 수 없다는 점입니다.

### 6.2 Sanitizer는 PII/민감정보 마스킹용
`HeaderSanitizer`는 `Authorization`, `Cookie` 같은 헤더를 span 속성에 넣지 않도록 걸러내고, `SqlSanitizer`는 SQL의 리터럴 값을 `?`로 치환해 바인딩된 실제 값이 trace에 남지 않게 합니다. 새로운 instrumentation을 추가할 때 사용자 입력이나 인증 정보가 그대로 span 속성에 들어가지 않도록, 이미 있는 sanitizer를 거치는 패턴을 따르는 게 안전합니다.

### 6.3 OTLP 전송은 Circuit Breaker로 보호된다
`OtlpHttpProtobufSender`는 연속 5회 전송 실패(`CB_FAILURE_THRESHOLD = 5`)가 누적되면 30초간(`CB_COOLDOWN_MS = 30_000`) 전송을 끊고, 그 사이 요청은 작은 버퍼 큐(`CB_QUEUE_CAPACITY = 16`)에만 쌓아둡니다. collector가 잠깐 죽어도 에이전트 자체가 느려지거나 메모리를 과도하게 먹지 않게 하려는 설계입니다. 반대로 이 기간 동안의 span/log/metric은 버퍼 용량을 넘으면 그냥 버려진다는 뜻이므로, collector 장애가 길어지면 데이터 손실이 발생합니다.

### 6.4 컨텍스트 전파는 ThreadLocal 기반 — 직접 만든 Thread에서는 끊긴다
`concurrent/` 패키지가 `ExecutorService`/`CompletableFuture`/Spring `@Async`를 통한 컨텍스트 전파는 advice로 처리해주지만, 애플리케이션 코드가 `new Thread(...)`로 직접 스레드를 만들면 에이전트가 이를 가로채지 않으므로 그 안에서는 부모 span 컨텍스트가 보이지 않습니다. 이런 코드 경로가 있는 서비스를 계측할 때는 이 한계를 미리 인지하고 있어야 합니다.

### 6.5 로컬 실행 스크립트의 하드코딩된 경로
`start.sh`/`start-with-remote-config.sh`는 특정 개발자의 로컬 경로(`/Users/kkc/APM/...`)를 그대로 사용합니다. CI나 다른 사람 환경에서 그대로 돌리면 실패하므로, 실제로는 직접 경로를 바꿔 쓰거나 새로운 범용 스크립트로 다시 작성하는 것이 필요해 보입니다(이 부분은 코드 상의 명백한 한계이며, 추측이 아니라 스크립트 내용을 그대로 옮긴 것입니다).

### 6.6 이 레포만으로는 풀 스택이 완성되지 않는다
`e2e-test.sh`, `e2e-k8s-test.sh`, `start-with-remote-config.sh`는 모두 이 레포 밖에 있는 `javi-collector`(Go로 작성된 것으로 추정), `javi-config-server`, (k8s 테스트의 경우) forecast 서비스의 존재를 전제로 합니다. 이 레포만 clone해서는 에이전트가 데이터를 만들어 보내는 것까지만 확인할 수 있고, 그걸 받아서 보여주는 쪽은 별도로 준비해야 합니다.

### 6.7 문서 자동화 체계
이 레포는 PR마다 GitHub Actions(`.github/workflows/sync-claudemd.yml`)가 두 문서를 자동으로 갱신합니다: `CLAUDE.md`의 자동 생성 구간은 결정론적 스크립트(`.github/scripts/gen-claudemd-auto.sh`)가, 서술형 구간과 이 문서(`docs/ARCHITECTURE.md`)는 Claude가 PR diff를 보고 갱신합니다. 따라서 `docs/ARCHITECTURE.md`를 사람이 직접 수정해도 다음 PR에서 덮어써질 수 있다는 점을 참고하세요.
