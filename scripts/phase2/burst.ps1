# Scenario 11: publish a burst of BookingConfirmed events straight into Kafka to watch consumer lag grow and drain.
# Usage:  .\scripts\phase2\burst.ps1 -Count 500
# Tip: slow the consumer first:  $env:NOTIFICATION_SIMULATED_DELAY_MS=200; docker compose up -d notification-service
param([int]$Count = 200)
$lines = 1..$Count | ForEach-Object {
    $id = [guid]::NewGuid().ToString(); $booking = [guid]::NewGuid().ToString()
    $json = '{"eventId":"' + $id + '","eventType":"BookingConfirmed","schemaVersion":2,"aggregateVersion":1,"bookingId":"' + $booking + '","customerId":"load","recipientEmail":"load' + $_ + '@example.com","showId":1,"eventTitle":"Load test","showStartTime":"2030-01-01T10:00:00Z","seats":["A1"],"totalAmount":100.00,"amount":100.00,"currency":"INR","seatCategory":"STANDARD"}'
    "$booking`:$json"
}
$lines | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic booking.events --property parse.key=true --property key.separator=:
Write-Host "Published $Count events. Watch: http://localhost:3000 (Kafka consumer lag panel) and http://localhost:8085 (Kafka UI)."
