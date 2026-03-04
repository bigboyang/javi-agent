# 1) agent 빌드 (기존 결과물 삭제 포함)
cd /Users/kkc/APM/agent
rm -rf target
mvn clean package

# 2) test-app 빌드 (기존 결과물 삭제 포함)
cd /Users/kkc/APM/test-app
rm -rf target
mvn clean package

# 3) 애플리케이션 실행 (agent 붙이기)
java -javaagent:/Users/kkc/APM/agent/target/javi-1.0.0.jar \
  -jar /Users/kkc/APM/test-app/target/test-0.0.1-SNAPSHOT.jar