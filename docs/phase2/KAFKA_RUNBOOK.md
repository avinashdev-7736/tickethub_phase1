# Kafka runbook (scenarios 0, 1, 11, 12)

Kafka UI: http://localhost:8085 (topics, messages, consumer groups and their lag). Everything below can also be done in the UI.
Commands assume the stack is up (`docker compose up -d`) and use the broker container. PowerShell and bash are the same.

## Scenario 0 - get to know the cluster
```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic booking.events
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notification-service
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic booking.events --from-beginning --property print.key=true --property print.headers=true
```
Read the `--describe --group` output as: CURRENT-OFFSET (where the group is), LOG-END-OFFSET (what has been written), LAG (the difference).
Partition = one ordered log; the **key** (`bookingId`) decides the partition; one partition is read by at most one consumer of a group at a time.

## Scenario 1 - poison messages and the dead-letter topic
1. `.\scripts\phase2\poison.ps1` publishes: not-JSON, JSON with no recipient, then a good event.
2. Expect: the good message is processed (email in Mailpit) - the poison did **not** block the partition.
3. Inspect (ADMIN token, direct port):
   `GET http://localhost:8084/admin/dead-letters`  (Authorization: Bearer <admin token>)
4. Fix the cause (data or code), then send one back: `POST http://localhost:8084/admin/dead-letters/{id}/replay`
   with optional body `{"payload":"{...corrected event...}"}`. The inbox makes sure an already-applied event is never applied twice.
How it works: bad data throws `InvalidEventException` -> no retry -> `booking.events.DLT` -> `@DltHandler` stores it in table `dead_letter`.
Provider trouble throws `TransientProcessingException` -> retried on delayed `booking.events.retry.*` topics (2s, 6s, 18s) -> then the DLT.
Booking and Payment use blocking bounded retries (500ms x 2^n, 4 retries) then their own `.DLT` topics (look at them in Kafka UI).

## Scenario 11 - consumer lag
1. Make the consumer slow: `$env:NOTIFICATION_SIMULATED_DELAY_MS=200; docker compose up -d notification-service` (bash: `NOTIFICATION_SIMULATED_DELAY_MS=200 docker compose up -d notification-service`).
2. `.\scripts\phase2\burst.ps1 -Count 500` (bash: `./scripts/phase2/burst.sh 500`).
3. Watch lag grow: Grafana panel "Kafka consumer lag" (http://localhost:3000), or `kafka-consumer-groups.sh --describe --group notification-service`. Prometheus alert `ConsumerLagHigh` fires after 1 minute above 100.
4. Add a consumer: `docker compose --profile scale up -d`. Lag drains about twice as fast: the group rebalances and the 3 partitions are shared.
5. Add more: with **3 partitions at most 3 consumers of one group do work**; a 4th instance would sit idle. More throughput than that needs more partitions (which changes key-to-partition mapping for new messages) or a faster consumer.
6. Reset the delay: `docker compose up -d notification-service` without the variable.
Measured numbers to write down in your notes: time to drain with 1 consumer, with 2, and what the alert showed.

## Scenario 12 - replay
Use case: the notification database was lost / a bug skipped records; rebuild from the topic **without emailing anyone again**.
1. `docker compose --profile replay up -d notification-replay`
   - new consumer group `notification-replay-1` (`REPLAY_GROUP_ID`), so it starts at the **beginning** of `booking.events`;
   - own database `notification_replay_db`; `NOTIFICATION_REPLAY_MODE=true`: it writes inbox + notification rows (status `REPLAYED`) and never sends mail.
2. Check progress: `kafka-consumer-groups.sh --describe --group notification-replay-1` (lag -> 0), Mailpit shows **no** new mail.
3. Compare: `SELECT status, count(*) FROM notification_replay_db.notification GROUP BY status;` against `notification_db.notification`.
4. To replay again from scratch: stop the instance, use a new `REPLAY_GROUP_ID`, or reset the offsets:
   `kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group notification-replay-1 --topic booking.events --reset-offsets --to-earliest --execute` (group must be stopped).
**Limits you must state:** replay can only go back as far as retention (`retention.ms` = 7 days on `booking.events`); older events are gone. Replay order is per partition, not global. A replay never "re-sends" emails by design; if the real intention is to resend, that is a different, deliberate operation.
Duplicates in the topic (outbox is at-least-once) are harmless: the inbox rejects them.
