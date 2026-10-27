# Scenario 1: publish poison messages. They must NOT block the topic: they go to the dead-letter topic, a good message behind them still works.
$good = [guid]::NewGuid().ToString()
$msgs = @(
  'poison1:this is not json at all',
  'poison2:{"eventId":"p2","eventType":"BookingConfirmed","bookingId":"poison2","recipientEmail":null}',
  ($good + ':{"eventId":"' + [guid]::NewGuid() + '","eventType":"BookingConfirmed","schemaVersion":2,"aggregateVersion":1,"bookingId":"' + $good + '","customerId":"c","recipientEmail":"after-poison@example.com","showId":1,"eventTitle":"After the poison","showStartTime":"2030-01-01T10:00:00Z","seats":["A1"],"totalAmount":100.00,"currency":"INR"}')
)
$msgs | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic booking.events --property parse.key=true --property key.separator=:
Write-Host "Done. Expect: 2 dead letters (GET http://localhost:8084/admin/dead-letters with an ADMIN token) and 1 email to after-poison@example.com in Mailpit (http://localhost:8025)."
