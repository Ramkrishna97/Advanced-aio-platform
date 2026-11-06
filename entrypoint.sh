#!/bin/sh

# Single JVM process running all microservices in-memory (Uses only ~90-120MB RAM total)
JAVA_OPTS="-Xms32m -Xmx256m -XX:+UseG1GC"

PORT="${PORT:-8080}"
STORAGE_ROOT="${STORAGE_ROOT:-/app/resources}"
mkdir -p "$STORAGE_ROOT/database" "$STORAGE_ROOT/data" "$STORAGE_ROOT/users" \
  "$STORAGE_ROOT/products" "$STORAGE_ROOT/feed" "$STORAGE_ROOT/classroom/videos" \
  "$STORAGE_ROOT/classroom/quizzes"
echo "Starting Unified All-in-One Backend Server on port $PORT..."
exec java $JAVA_OPTS -jar /app/dashboard-services-0.1.0.jar
