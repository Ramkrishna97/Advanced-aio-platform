#!/bin/sh

# Set low memory overhead for each JVM instance so all microservices run inside container RAM
JAVA_OPTS="-Xms32m -Xmx64m -XX:+UseG1GC"

echo "Starting discovery-service..."
java $JAVA_OPTS -jar /app/discovery-service.jar &
sleep 4

echo "Starting backend microservices..."
java $JAVA_OPTS -jar /app/auth-service.jar &
java $JAVA_OPTS -jar /app/catalog-service.jar &
java $JAVA_OPTS -jar /app/cart-service.jar &
java $JAVA_OPTS -jar /app/classroom-service.jar &
java $JAVA_OPTS -jar /app/feed-service.jar &
java $JAVA_OPTS -jar /app/studio-editor-service.jar &
java $JAVA_OPTS -jar /app/quiz-service.jar &
java $JAVA_OPTS -jar /app/order-delivery-service.jar &

sleep 3

PORT="${PORT:-8080}"
echo "Starting API Gateway on port $PORT..."
exec java $JAVA_OPTS -jar /app/gateway.jar
