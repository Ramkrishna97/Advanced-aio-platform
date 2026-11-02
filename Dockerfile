# Stage 1: Build Java microservices with Gradle & JDK 17
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY Backend /app/Backend
WORKDIR /app/Backend
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy compiled JAR files from builder
COPY --from=builder /app/Backend/discovery-service/build/libs/discovery-service-*.jar /app/discovery-service.jar
COPY --from=builder /app/Backend/gateway/build/libs/gateway-*.jar /app/gateway.jar
COPY --from=builder /app/Backend/auth-service/build/libs/auth-service-*.jar /app/auth-service.jar
COPY --from=builder /app/Backend/catalog-service/build/libs/catalog-service-*.jar /app/catalog-service.jar
COPY --from=builder /app/Backend/cart-service/build/libs/cart-service-*.jar /app/cart-service.jar
COPY --from=builder /app/Backend/classroom-service/build/libs/classroom-service-*.jar /app/classroom-service.jar
COPY --from=builder /app/Backend/feed-service/build/libs/feed-service-*.jar /app/feed-service.jar
COPY --from=builder /app/Backend/studio-editor-service/build/libs/studio-editor-service-*.jar /app/studio-editor-service.jar
COPY --from=builder /app/Backend/quiz-service/build/libs/quiz-service-*.jar /app/quiz-service.jar
COPY --from=builder /app/Backend/order-delivery-service/build/libs/order-delivery-service-*.jar /app/order-delivery-service.jar

# Copy resources and data files
COPY resources /app/resources

# Copy startup script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
