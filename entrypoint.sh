#!/bin/sh

echo "Starting discovery-service..."
java -jar /app/discovery-service.jar &
sleep 5

echo "Starting backend microservices..."
java -jar /app/auth-service.jar &
java -jar /app/catalog-service.jar &
java -jar /app/cart-service.jar &
java -jar /app/classroom-service.jar &
java -jar /app/feed-service.jar &
java -jar /app/studio-editor-service.jar &
java -jar /app/quiz-service.jar &
java -jar /app/order-delivery-service.jar &

sleep 5

echo "Starting API Gateway on port 8080..."
exec java -jar /app/gateway.jar
