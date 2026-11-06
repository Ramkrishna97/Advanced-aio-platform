# Stage 1: Build unified backend JAR with Gradle & JDK 17
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY Backend /app/Backend
WORKDIR /app/Backend
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
ENV STORAGE_ROOT=/app/resources

# Copy the single compiled unified JAR
COPY --from=builder /app/Backend/build/libs/dashboard-services-0.1.0.jar /app/dashboard-services-0.1.0.jar

# Copy resources and data files
COPY resources /app/resources

# Copy startup script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
