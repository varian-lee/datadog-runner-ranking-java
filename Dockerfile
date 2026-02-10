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

# Datadog Java Agent 다운로드
ADD https://dtdg.co/latest-java-tracer /app/dd-java-agent.jar

COPY --from=build /build/target/ranking-java.jar app.jar
EXPOSE 8081

# Datadog Java Agent와 함께 실행
ENV DD_SERVICE=ranking-java
ENV DD_ENV=demo
ENV DD_LOGS_INJECTION=true
ENV DD_TRACE_ENABLED=true

ENTRYPOINT ["java", "-javaagent:/app/dd-java-agent.jar", "-jar", "/app/app.jar"]
