# build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# run stage
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/target/ranking-java.jar app.jar
EXPOSE 8081

# OpenTelemetry Manual Instrumentation만 사용
# Auto-instrumentation은 각 벤더의 javaagent가 담당:
# - Datadog: Kubernetes Admission Controller가 dd-java-agent.jar 주입
# - New Relic: initContainer 또는 Admission Controller가 newrelic-agent.jar 주입
# - OpenTelemetry: 필요 시 K8s Operator가 otel-javaagent.jar 주입
ENTRYPOINT ["java","-jar","/app/app.jar"]
