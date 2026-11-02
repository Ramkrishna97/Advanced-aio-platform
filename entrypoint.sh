#!/bin/sh

# Single JVM process running all microservices in-memory (Uses only ~90-120MB RAM total)
JAVA_OPTS="-Xms32m -Xmx256m -XX:+UseG1GC"

PORT="${PORT:-8080}"
echo "Starting Unified All-in-One Backend Server on port $PORT..."
exec java $JAVA_OPTS -jar /app/dashboard-services-0.1.0.jar
