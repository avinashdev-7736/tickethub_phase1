#!/usr/bin/env bash
# Scenario 11: publish a burst of BookingConfirmed events straight into Kafka.   Usage: ./scripts/phase2/burst.sh 500
set -euo pipefail
COUNT=${1:-200}
for i in $(seq 1 "$COUNT"); do
  id=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen); booking=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
  echo "$booking:{\"eventId\":\"$id\",\"eventType\":\"BookingConfirmed\",\"schemaVersion\":2,\"aggregateVersion\":1,\"bookingId\":\"$booking\",\"customerId\":\"load\",\"recipientEmail\":\"load$i@example.com\",\"showId\":1,\"eventTitle\":\"Load test\",\"showStartTime\":\"2030-01-01T10:00:00Z\",\"seats\":[\"A1\"],\"totalAmount\":100.00,\"amount\":100.00,\"currency\":\"INR\",\"seatCategory\":\"STANDARD\"}"
done | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic booking.events --property parse.key=true --property key.separator=:
echo "Published $COUNT events."
