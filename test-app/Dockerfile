FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY test-app/target/test-0.0.1-SNAPSHOT.jar app.jar
COPY agent/target/javi-1.0.0.jar javi-agent.jar

ENV JAVI_SERVICE_NAME=e2e-test-app \
    JAVI_COLLECTOR_ENDPOINT=http://javi-collector:4318 \
    SPRING_PROFILES_ACTIVE=e2e

EXPOSE 8080

ENTRYPOINT ["java", \
  "-javaagent:/app/javi-agent.jar", \
  "-Dcom.agent.shaded.bytebuddy.experimental=true", \
  "-Dnet.bytebuddy.experimental=true", \
  "-jar", "/app/app.jar"]
