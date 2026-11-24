# RackWatch API — multi-stage build.
# Stage 1 builds the jar with Maven (needs Maven Central).
# Stage 2 runs it on a JRE, as a non-root user (UID 10001).
# wget is installed explicitly so the compose healthcheck
# (wget http://localhost:8080/actuator/health) works even if the
# base image ever drops BusyBox wget.
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# UTC everywhere: log timestamps and the Excel report's
# "Timestamp (UTC)" column are UTC because of this (also set in
# docker-compose.yml for the compose stack).
ENV TZ=UTC
RUN apk add --no-cache wget \
    && addgroup -S -g 10001 rackwatch \
    && adduser -S -u 10001 -G rackwatch rackwatch
WORKDIR /app
COPY --from=build /build/target/rackwatch-api-*.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
