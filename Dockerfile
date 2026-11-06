# Stage 1: Build unified backend JAR with Gradle & JDK 17
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY Backend /app/Backend
WORKDIR /app/Backend
RUN chmod +x gradlew && ./gradlew :bootJar --no-daemon --max-workers=2

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
ENV STORAGE_ROOT=/app/resources

# Copy the single compiled unified JAR. Keeping a stable runtime name avoids
# coupling the container command to the Gradle project version.
COPY --from=builder /app/Backend/build/libs/dashboard-services-*.jar /app/application.jar

# A deployment starts with empty runtime storage. Local databases, uploads and
# videos never enter the image, regardless of how large resources/ becomes.
COPY resources/products/default.svg /app/resources/products/default.svg
COPY resources/classroom/quizzes/schema.json /app/resources/classroom/quizzes/schema.json

# Copy startup script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
