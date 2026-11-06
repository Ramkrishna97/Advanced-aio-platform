#!/bin/sh

# Keep the unified service inside Render's memory limit. Serial GC has much
# lower native-memory overhead than G1 for this small, single-JVM workload.
JAVA_OPTS="${JAVA_OPTS:--Xms32m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m}"

PORT="${PORT:-8080}"
STORAGE_ROOT="${STORAGE_ROOT:-/app/resources}"
mkdir -p "$STORAGE_ROOT/database" "$STORAGE_ROOT/data" "$STORAGE_ROOT/users" \
  "$STORAGE_ROOT/products" "$STORAGE_ROOT/feed" "$STORAGE_ROOT/classroom/videos" \
  "$STORAGE_ROOT/classroom/quizzes"
echo "Starting Unified All-in-One Backend Server on port $PORT..."
exec java $JAVA_OPTS -jar /app/application.jar
