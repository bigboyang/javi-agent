#!/bin/bash

# 1) Docker 인프라 실행
echo ">>> 인프라(Redis, Kafka, RabbitMQ, Jaeger, Prometheus)를 실행합니다..."
docker-compose up -d

# 서비스가 준비될 때까지 잠시 대기 (특히 Kafka)
echo ">>> 서비스가 준비될 때까지 대기 중 (15초)..."
sleep 15

# 2) agent 빌드
echo ">>> 에이전트 빌드 중..."
cd /Users/kkc/APM/agent
mvn clean package -DskipTests

# 3) test-app 빌드
echo ">>> 테스트 앱 빌드 중..."
cd /Users/kkc/APM/test-app
mvn clean package -DskipTests

# 4) 애플리케이션 실행 (agent 부착)
echo ">>> 애플리케이션 실행 중..."
cd /Users/kkc/APM

# 로그 디렉토리 생성
mkdir -p /Users/kkc/APM/javi/logs

# java -javaagent:/Users/kkc/APM/agent/target/javi-1.0.0.jar \
#   -Djavi.log.file=/Users/kkc/APM/javi/logs/javi-agent.log \
#   -Djavi.log.level=FINE \
#   -Djavi.trace.log.file=/Users/kkc/APM/javi/logs/javi-traces.log \
#   -jar /Users/kkc/APM/test-app/target/test-0.0.1-SNAPSHOT.jar


java -javaagent:/Users/kkc/APM/agent/target/javi-1.0.0.jar \
  -Djavi.log.file=/Users/kkc/APM/javi/logs/javi-agent.log \
  -Djavi.log.level=FINE \
  -Djavi.trace.log.file=/Users/kkc/APM/javi/logs/javi-traces.log \
  -Djavi.remote.config.url=http://localhost:18888/api/config/remote \
  -Djavi.remote.config.poll.interval.sec=30 \
  -jar /Users/kkc/APM/test-app/target/test-0.0.1-SNAPSHOT.jar