# TicketHub Phase 1 - Build Progress

Resume rule: continue from the first unchecked item. Never redo checked items.
Environment note: no Maven/Docker/Maven Central in the build sandbox, so code is syntax-checked only, NOT compiled or run.

Decisions: Keycloak auth (JWT, realm roles CUSTOMER/ADMIN) | MySQL per service | Kafka (KRaft) | Individual (no teams)

## Foundation
- [x] Root pom, services pom, platform pom
- [x] common module (error format, JWT role converter, correlation id, internal key, logback)

## Services (S0-S5 core flow)
- [x] event-service (entities, admin+customer APIs, seat map via Booking client w/ CB+retry, S3 poster, seeder, 1 unit test, internal show view)
- [x] booking-service (create w/ DB-enforced seat uniqueness, idempotency key, server-side amount, expiry job, cancel, payment-result, reserved-seats, Kafka publisher, 7 tests)
- [x] payment-service (initiate from booking amount, simulated provider w/ runtime modes, Razorpay sandbox provider, signed idempotent webhook, booking sync status, 7 tests)
- [x] notification-service (Kafka consumer, UNIQUE(event_id) dedupe, SENT/FAILED tracking, never throws, 4 tests)

## Platform (S6-S8)
- [x] discovery-server
- [x] config-server (native, classpath config-repo, non-secret tunables only)
- [x] api-gateway (routes, JWT check, /internal denied, correlation id; NO rate limiting - needs Redis, deferred)
- [x] Keycloak realm (infra/keycloak, users admin/customer1/customer2)

## Infra / ops (S9-S13)
- [x] docker-compose (mysql, kafka, keycloak, mailpit, jaeger, prometheus, grafana, elk profile, failover profile)
- [x] Dockerfile, CI workflow
- [x] Prometheus/Grafana/Logstash config

## Docs
- [x] README, API doc, decisions, deployment (RDS/S3/Secrets), evidence checklist, demo scripts

## Verification
- [x] Java syntax check of all 77 sources with javac's parser: 0 syntax errors; remaining javac messages are only missing Spring/Lombok/JUnit jars (no Maven Central here)
- [ ] **YOU: `mvn -B verify`** (first real compile + 25 tests) - fix anything it reports
- [ ] **YOU: `docker compose up -d --build`, then `./scripts/demo.sh` and `./scripts/concurrency-demo.sh`**
- [ ] **YOU: provide AWS (RDS, S3, Secrets), payment sandbox keys, SMTP** for the S11-S12 items (docs/DEPLOYMENT.md)


## Phase 1 status by backlog sprint (code written, not yet run)
S0-S1 analysis/contracts: docs/API.md, docs/DECISIONS.md | S2-S5 core flow: event/booking/payment services | S6 discovery+gateway+config | S7 Keycloak/JWT |
S8 timeouts/retry/circuit breaker | S9 Kafka+notification | S10 logs/metrics/traces | S11 sandbox payment, email, S3 | S12 Docker/CI/RDS docs | S13 demo scripts + evidence checklist

## Deferred / not built
Gateway rate limiting (needs Redis); automatic SOLD_OUT/COMPLETED; Flyway; refunds/outbox/DLT/saga (Phase 2).

## Build fixes after first real `mvn verify`
- [x] common: RestClientBuilderConfigurer import moved to org.springframework.boot.autoconfigure.web.client; auto-config ordered before RestClientAutoConfiguration
- [ ] next: re-run `mvn -B verify -rf :tickethub-common` and report the next error
- [x] First real `mvn verify` results: common OK, event-service 3/3, booking-service 7/7, payment-service 7/7 tests pass
- [x] notification test fix: mocked JavaMailSender broke mail health indicator -> added management.health.mail.enabled=false to NotificationFlowTest
- [ ] next: `mvn -B verify -rf :notification-service` (then discovery-server, config-server, api-gateway build)
- [x] Added PowerShell demo scripts (scripts/demo.ps1, scripts/concurrency-demo.ps1) because the user runs Windows PowerShell

# ===================== PHASE 2 (source: TicketHub_Phase2_Student_Guide.html) =====================
Resume rule: continue from the first unchecked item. Base = Phase 1 code (builds, tests passed on user's machine).
Event contract (decided): booking events -> topic `booking.events` (BookingConfirmed/BookingRejected/BookingCancelled),
payment events -> topic `payment.events` (PaymentSucceeded/PaymentFailed/PaymentRefunded). Key = bookingId. One topic per producer so
one booking's events share a partition (scenario 6). Envelope: eventId, eventType, schemaVersion, occurredAt, bookingId, aggregateVersion, correlationId + payload.
Phase 1 topic `booking-confirmed` is replaced by `booking.events`; the REST payment-result callback is removed (scenario 4).

## Part A - make messaging reliable
- [x] Baseline: correlation id in Kafka headers (outbox -> header -> MDC), observation enabled, lag/outbox/DLT panels + alert rules
- [x] S0 Kafka UI in compose (runbook in docs/phase2)
- [x] S1 Notification: non-blocking retry + DLT (@RetryableTopic), poison -> DLT, dead-letter admin list/replay
- [x] S2 Notification inbox + atomic claim (duplicates, parallel instances)
- [x] S3 Booking transactional outbox + relay (event survives Kafka outage)
## Part B - saga
- [x] S4 Payment outbox PaymentSucceeded/Failed; Booking consumes with inbox; REST callback removed
- [x] S5 Compensation: BookingRejected/BookingCancelled -> idempotent refund -> PaymentRefunded
- [x] S6 Ordering: key=bookingId + aggregateVersion, stale events ignored (Booking + Notification sides)
- [x] S7 Saga timeline (state log) + decision note (choreography vs orchestration)
- [x] S8 Reconciliation job + report + seed endpoints
## Part C - harden outside systems
- [x] S9 Payment forward-only state machine, UNKNOWN on timeout + resolve, webhook dedupe/reorder
- [x] S10 Email: RETRYING, retry topics, circuit breaker + rate limit
## Part D - operate and evolve Kafka
- [x] S11 Lag: burst scripts, NOTIFICATION_SIMULATED_DELAY_MS, scale profile, Grafana + alert
- [x] S12 Replay mode (new group, no emails) + runbook
- [x] S13 contract v2 (producer dual fields amount/totalAmount + seatCategory in BookingEvents; tolerant consumer + tests)
## Part E
- [x] S14 chaos.ps1 (8 drills) + docs/phase2/INCIDENTS.md (Observed fields left for the student to fill in)
## Tests / docs / delivery
- [x] Update Phase 1 tests broken by design change (booking, payment, notification)
- [x] New tests per scenario (booking 13, payment 17, notification 10; DLT/retry-topic wiring NOT covered by unit tests - see docs)
- [x] docs/phase2/* , README, API.md, DECISIONS.md, EVIDENCE_CHECKLIST.md
- [x] javac parse of 122 files: 0 syntax errors; cross-check of members of own classes: 0 unexplained; [x] zip

## PHASE 2 STATUS: code, tests, docs and scripts complete. NOT compiled or run in the build sandbox.
Next for the user: commit current state, copy these files over the repo (keep .git), `mvn -B verify`, `docker compose down -v && docker compose up -d --build`,
then run the drills in docs/phase2/INCIDENTS.md. Known deviations from the guide: topic names (booking.events / payment.events instead of one topic per event type - needed for per-booking ordering);
scenario 7 orchestration prototype = design note only (SAGA.md), not code; scenario 11/14 numbers and 'Observed' fields must be measured on a running stack.
