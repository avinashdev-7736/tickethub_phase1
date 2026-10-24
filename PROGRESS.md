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
