# Javi Agent gRPC 전송 파이프라인/코드 정리 (현황)

- 작성일: 2026-04-03
- 대상: `/Users/kkc/APM`
- 목적: "지금 gRPC로 보내는 코드/파이프라인"을 실제 코드 기준으로 정리

## 1) 결론 요약

1. 현재 코드에서 `protocol=grpc` 경로는 **이름은 gRPC**지만, 실제 전송은 **OTLP/HTTP Protobuf** 방식이다.
2. 근거:
- `GrpcSender` 주석: "OTLP/HTTP Protobuf 전송" + "gRPC 5-byte framing 없음"
- `HttpClient.Version.HTTP_1_1` 사용
- `Content-Type: application/x-protobuf`
- 경로는 `/v1/traces`, `/v1/metrics`, `/v1/logs` HTTP endpoint
3. 즉, 현재는 "gRPC 네이티브(HTTP/2 + application/grpc + gRPC framing)"가 아니라 "OTLP HTTP protobuf" 파이프라인이다.

---

## 2) 런타임 분기(프로토콜 선택)

기준 파일: `agent/src/main/java/com/agent/instrumentation/AgentRuntime.java`

- 분기 키: `AgentConfig.getExporterProtocol()`
- 기본값: `grpc` (`AgentConfig`)
- 분기 동작:
  - `http`면 `OtlpHttp*Exporter` 경로
  - 그 외(`grpc` 기본)면 `OtlpGrpc*Exporter` + `GrpcSender` 경로

중요 포인트:
- 로그 메시지도 `프로토콜: OTLP/HTTP Protobuf`로 출력됨
- 즉 "grpc 분기"라는 명명과 달리 실제 transport는 HTTP protobuf로 구현됨

---

## 3) 시그널별 전송 파이프라인

## 3.1 Trace

경로:
1. Span 종료
2. `BatchSpanProcessor` 배치 수집
3. `OtlpGrpcSpanExporter.export(...)`
4. `encodeExportRequest(...)`로 OTLP trace protobuf payload 생성
5. `GrpcSender.send("/v1/traces", payload)` 호출
6. Collector `otlp/http :4318` 수신

관련 파일:
- `agent/src/main/java/com/agent/trace/processor/BatchSpanProcessor.java`
- `agent/src/main/java/com/agent/trace/exporter/OtlpGrpcSpanExporter.java`
- `agent/src/main/java/com/agent/common/grpc/GrpcSender.java`

## 3.2 Metrics

경로:
1. `SdkMeterProvider` -> `MetricBatchProcessor`
2. `OtlpGrpcMetricExporter.export(...)`
3. OTLP metrics protobuf payload 인코딩
4. `GrpcSender.send("/v1/metrics", payload)`
5. Collector `otlp/http :4318` 수신

관련 파일:
- `agent/src/main/java/com/agent/metric/SdkMeterProvider.java`
- `agent/src/main/java/com/agent/metric/MetricBatchProcessor.java`
- `agent/src/main/java/com/agent/metric/OtlpGrpcMetricExporter.java`
- `agent/src/main/java/com/agent/common/grpc/GrpcSender.java`

## 3.3 Logs

경로:
1. `SdkLoggerProvider` -> `LogBatchProcessor`
2. `OtlpGrpcLogExporter.export(...)`
3. OTLP logs protobuf payload 인코딩
4. `GrpcSender.send("/v1/logs", payload)`
5. Collector `otlp/http :4318` 수신

관련 파일:
- `agent/src/main/java/com/agent/logs/SdkLoggerProvider.java`
- `agent/src/main/java/com/agent/logs/LogBatchProcessor.java`
- `agent/src/main/java/com/agent/logs/OtlpGrpcLogExporter.java`
- `agent/src/main/java/com/agent/common/grpc/GrpcSender.java`

---

## 4) `GrpcSender` 실제 동작 상세

기준 파일: `agent/src/main/java/com/agent/common/grpc/GrpcSender.java`

## 4.1 Transport

- Java `HttpClient` 사용
- `HttpClient.Version.HTTP_1_1` 고정
- 요청 헤더: `content-type: application/x-protobuf`
- URL: `baseEndpoint + "/v1/..."`

=> gRPC 네이티브 필수 요소인 `application/grpc`, HTTP/2 스트림 기반 RPC framing은 사용하지 않음.

## 4.2 재시도/회로차단

- retry backoff: 0s, 1s, 2s
- max attempts: 3
- Circuit Breaker:
  - 연속 실패 5회 -> OPEN
  - 30초 후 HALF_OPEN probe
  - 성공 시 CLOSED 복구
- 4xx는 재시도 없음, 5xx/예외는 재시도

## 4.3 보안/헤더/압축

- TLS/mTLS 설정 지원 (`JAVI_TLS_*`)
- 커스텀 OTLP 헤더 지원 (`JAVI_OTLP_HEADERS`, `OTEL_EXPORTER_OTLP_HEADERS`)
- gzip 압축 옵션 (`JAVI_OTLP_COMPRESSION`)

---

## 5) Exporter 인코딩 구조

공통:
- `ProtoEncoder`로 수동 protobuf 인코딩
- Resource/Scope/Signal payload를 OTLP proto field 번호에 맞춰 직렬화

### Trace Exporter
- 파일: `OtlpGrpcSpanExporter`
- path 상수: `/v1/traces`
- scope별 그룹핑 후 `ExportTraceServiceRequest` 인코딩

### Metrics Exporter
- 파일: `OtlpGrpcMetricExporter`
- path 상수: `/v1/metrics`
- Gauge/Sum/Histogram(Exemplar 포함) 인코딩

### Logs Exporter
- 파일: `OtlpGrpcLogExporter`
- path 상수: `/v1/logs`
- severity/body/attributes/trace_id/span_id 인코딩

---

## 6) Collector 수신 파이프라인 매핑

기준 파일:
- `otel-collector-config.yaml`
- `docker-compose.yml`

설정:
- receiver otlp:
  - grpc: `0.0.0.0:4317`
  - http: `0.0.0.0:4318`
- 서비스 포트 노출:
  - `4317:4317` (OTLP gRPC)
  - `4318:4318` (OTLP HTTP)

현재 Agent 경로는 코드상 `4318` + `/v1/...` 호출이므로 Collector의 `otlp/http`와 연결됨.

---

## 7) 설정값(중요)

기준 파일: `agent/src/main/java/com/agent/config/AgentConfig.java`

- `JAVI_EXPORTER_PROTOCOL` 기본값: `grpc`
- `JAVI_GRPC_ENDPOINT` 기본값: `http://localhost:4318`
- `JAVI_EXPORTER_ENDPOINT` 기본값: `http://localhost:4318/v1/traces`

주의:
- `grpc` 프로토콜 기본 endpoint가 `4318`인 점 자체가 현재 구현이 HTTP OTLP 경로임을 시사

---

## 8) 용어 정리 (팀 내 합의 권장)

현재 상태를 정확히 표현하면:
- "gRPC exporter"라는 클래스명/분기명은 존재
- 하지만 실제 transport는
  - **OTLP/HTTP Protobuf**
  - HTTP/1.1
  - `/v1/*` endpoint

따라서 운영 문서에는 다음과 같이 표기 권장:
- "현재 전송 방식: OTLP/HTTP Protobuf (gRPC native 아님)"

---

## 9) 확인 체크리스트

1. Agent 기동 로그에서 `프로토콜: OTLP/HTTP Protobuf endpoint=...` 확인
2. Collector 쪽 `4318` 수신 로그 확인
3. 트레이스/메트릭/로그 각각 `/v1/traces|metrics|logs` 요청 확인
4. 장애 시 retry/CB 동작(재시도 3회, OPEN/HALF_OPEN) 확인

---

## 10) 다음 단계 제안

1. 네이티브 gRPC 전환 필요 시:
- HTTP/2 + `application/grpc` + gRPC framing + OTLP service method 호출로 sender 재구현
2. 현재 방식 유지 시:
- 클래스/로그 명명(`GrpcSender`, `OtlpGrpc*Exporter`)을 `OtlpHttpProto*` 계열로 정리해 혼동 제거
3. 운영 가시성 보강:
- 전송 성공률/지연/CB 상태를 대시보드 표준 지표로 고정

