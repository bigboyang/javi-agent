#!/bin/bash

# APM Agent 실행 스크립트
#
# 사전 조건: config-server를 먼저 실행해야 한다.
#   cd /Users/kkc/javi-config-server
#   mvn spring-boot:run
#   (또는) java -jar target/config-server-1.0.0.jar
#
# Config Server API (port 18888):
#   GET  /api/config/remote                   - Agent polling endpoint (flat JSON)
#   GET  /api/config                          - 현재 설정 조회
#   PUT  /api/config  (JSON body)             - 전체 설정 교체
#   PATCH /api/config/{key}?value={val}       - 단일 필드 업데이트
#
# 예시:
#   curl http://localhost:18888/api/config
#   curl -X PATCH "http://localhost:18888/api/config/emergencyOff?value=true"
#   curl -X PATCH "http://localhost:18888/api/config/headSampleRate?value=0.5"
#   curl -X PATCH "http://localhost:18888/api/config/logInjection?value=false"

mkdir -p /Users/kkc/APM/javi/logs

java -javaagent:/Users/kkc/APM/agent/target/javi-1.0.0.jar \
  -Djavi.log.file=/Users/kkc/APM/javi/logs/javi-agent.log \
  -Djavi.log.level=FINE \
  -Djavi.trace.log.file=/Users/kkc/APM/javi/logs/javi-traces.log \
  -Djavi.remote.config.url=http://localhost:18888/api/config/remote \
  -Djavi.remote.config.poll.interval.sec=30 \
  -jar /Users/kkc/APM/test-app/target/test-0.0.1-SNAPSHOT.jar
