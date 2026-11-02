#!/bin/sh

# Ultra-low memory flags for 512MB RAM container limits
# -Xms12m -Xmx24m: Heap capped at 24MB
# -XX:MaxMetaspaceSize=26m: Metaspace capped at 26MB
# -XX:TieredStopAtLevel=1: Disable heavy C2 compiler (saves 30MB per JVM)
# -XX:+UseSerialGC: Use lightweight GC (0MB GC overhead memory)
JAVA_OPTS="-Xms12m -Xmx24m -XX:MaxMetaspaceSize=26m -XX:ReservedCodeCacheSize=12m -Xss256k -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"

echo "Starting backend microservices sequentially to prevent memory spikes..."

java $JAVA_OPTS -jar /app/auth-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/catalog-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/cart-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/classroom-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/feed-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/studio-editor-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/quiz-service.jar &
sleep 2

java $JAVA_OPTS -jar /app/order-delivery-service.jar &
sleep 2

PORT="${PORT:-8080}"
echo "Starting API Gateway on port $PORT..."
exec java $JAVA_OPTS -jar /app/gateway.jar
