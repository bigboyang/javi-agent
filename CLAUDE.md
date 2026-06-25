# javi-agent (APM)

javi APM 에이전트. **Java(Maven)** 로 작성된 관측 에이전트로, OTLP/protobuf로 텔레메트리를 전송한다(OpenTelemetry 스타일 SDK 자체 구현).

## 아키텍처

- `agent/` — 에이전트 본체 (`agent/pom.xml`, `agent/src/main/java/com/agent/...`)
  - `common/` — SDK(`JaviSdk`), OTLP 전송기, protobuf 인코더, sanitizer, id 생성기 등
- `test-app/` — 에이전트를 붙여 검증하는 테스트 앱 (`test-app/pom.xml`)
- 스크립트: `start.sh`, `start-with-remote-config.sh`, `e2e-test.sh`, `e2e-k8s-test.sh`

## 빌드·실행

- 빌드: `cd agent && mvn package`
- 실행: `./start.sh` (원격 설정 사용 시 `./start-with-remote-config.sh`)
- e2e: `./e2e-test.sh`, `./e2e-k8s-test.sh`

## 규칙·관례

> 코딩 컨벤션·주의사항을 여기에 적어두세요. PR로 코드가 바뀌면 이 영역은 GitHub Actions(Claude)가 자동 보강합니다.

<!-- AUTO-GENERATED:start (스크립트가 관리. 직접 수정 금지) -->

_아래 구간은 스크립트가 자동 생성합니다. 직접 수정하지 마세요._

### 기술 스택
- (자동 감지된 매니페스트 없음)

### 명령어

### 최상위 디렉터리 구조
```
.github
agent
test-app
```

<!-- AUTO-GENERATED:end -->
