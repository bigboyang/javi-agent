# Java APM Agent Javi

--- 

Core 모델
Trace/Span/SpanContext

Tracer (span 생성/종료)

Processor

Context propagation (ThreadLocal, MDC)

Metrics

Meter, Counter/Timer/Gauge

Registry(in-memory)

Exporter

콘솔/파일 먼저 → 나중에 OTLP/HTTP

Logging 연동

MDC에 traceId/spanId 넣기

Instrumentation (Byte Buddy)

HTTP(서블릿/스프링) 먼저

그 다음 JDBC

필요하면 Feign/RestTemplate 등 클라이언트

필수로 더 있어야 하는 것

Config: 샘플링, exporter URL, service name
Sampler: trace 샘플링 정책
Clock/ID generator
Error handling: span에 예외 기록


---


자동 계측(ByteBuddy) 연결: HTTP/DB/스레드 풀/큐 등에서 스팬 자동 생성
실제 Exporter 구현: OTLP/HTTP 전송, 실패 재시도, 백오프, 타임아웃
샘플러(Sampler): 확률/룰 기반 샘플링, 헤더 기반 결정
리소스/서비스 메타데이터: service.name, env, host 정보 등 공통 리소스
메트릭 수집기: span/queue/drop 카운터, exporter 실패율 같은 내부 텔레메트리
설정 시스템: config 파일/env/시스템 프로퍼티, 동적 갱신
테스트/검증: 컨텍스트 전파, 병렬/비동기 환경, 성능 테스트

