# TicketHub - Phase 1

Event / movie ticket booking built as Spring Boot microservices.

```
                      +--------------------+
 Client ──JWT──►      |    API Gateway     |  validates JWT, routes by service name, blocks /internal/**
                      +---------┬----------+
          lb://event  │  lb://booking   │  lb://payment
        ┌─────────────┘        │        └──────────────┐
 ┌──────▼──────┐   ┌──────────▼────────┐   ┌───────────▼───────┐
 │ Event/Show  │◄──│  Booking Service  │◄──│  Payment Service  │──► payment provider
 │  event_db   │   │    booking_db     │   │    payment_db     │    (simulated | sandbox)
 └─────────────┘   └─────────┬─────────┘   └───────────────────┘
   seat map asks Booking     │ BookingConfirmed (Kafka, key = bookingId)
   for reservation state     ▼
                    ┌───────────────────┐
                    │ Notification Svc  │──► SMTP (Mailpit locally)
                    │  notification_db  │
                    └───────────────────┘
 Platform: Eureka (discovery) · Config Server · Keycloak (JWT) · Jaeger · Prometheus/Grafana · ELK (optional)
```

## Run it
Prerequisites: Docker (about 8 GB RAM free; ELK needs more), nothing else.

```bash
docker compose up -d --build          # first build takes several minutes
docker compose ps                      # wait until services are "healthy"
./scripts/demo.sh                      # browse -> book -> pay -> confirmed -> email
./scripts/concurrency-demo.sh          # two customers, one seat: one 201, one 409
```
Java/Maven route (without Docker for the services): `mvn -B verify`, start the infra with
`docker compose up -d mysql kafka keycloak mailpit jaeger discovery-server config-server`, then run each
service's `*Application` class from the IDE (defaults point at localhost).

| What | URL | Login |
|---|---|---|
| API gateway (the only client entry point) | http://localhost:8080 | JWT |
| Keycloak admin console | http://localhost:8180 | admin / admin |
| Eureka dashboard | http://localhost:8761 | - |
| Mailpit (received emails) | http://localhost:8025 | - |
| Jaeger (traces) | http://localhost:16686 | - |
| Prometheus / Grafana | http://localhost:9090 / http://localhost:3000 | Grafana admin / admin |
| Kibana (profile `elk`) | http://localhost:5601 | - |
| Swagger UI per service | http://localhost:8081/swagger-ui.html (event), 8082 booking, 8083 payment | - |

**Test users** (realm `tickethub`): `admin / admin123` (ADMIN), `customer1 / customer123`, `customer2 / customer123` (CUSTOMER).

Get a token:
```bash
curl -s -d client_id=tickethub-web -d grant_type=password -d username=customer1 -d password=customer123 \
  http://localhost:8180/realms/tickethub/protocol/openid-connect/token
```
Demo data (venue, a PUBLISHED event and an OPEN show) is seeded on first start (`SEED_ENABLED=false` to disable).

## Failure scenarios worth demonstrating
| Scenario | How |
|---|---|
| Payment failure releases seats | `POST /payments/simulator/mode?mode=FAILURE` (ADMIN token), then book + pay |
| Payment stuck / timeout -> expiry | mode=`PENDING`, book + pay, wait for `booking.expiry-minutes` (set it to 1 in `config-repo/booking-service.yml`) |
| Slow payment provider (observability) | `.../mode?mode=SUCCESS&delayMs=4000`, then look at the trace in Jaeger |
| Notification failure never affects booking | `docker compose stop mailpit`, pay: booking CONFIRMED, notification FAILED |
| Kafka down at confirmation | stop kafka, pay: booking CONFIRMED, log `FAILED to publish`, metric `tickethub_booking_event_publish_failed_total` (Phase 2: outbox) |
| Service failover | `docker compose --profile failover up -d`, then stop `booking-service`, calls keep working via instance 2 |
| Circuit breaker | stop `booking-service`, call `GET /shows/{id}/seats` repeatedly: fast 503 `SEAT_STATE_UNAVAILABLE`, `/actuator/circuitbreakers` shows OPEN |
| Duplicate webhook | send the same signed webhook twice; applied once (see `PaymentFlowTest`) |

## Documents
- `docs/API.md` - endpoints, errors, status models
- `docs/DECISIONS.md` - design decisions, trade-offs and **known Phase 1 gaps**
- `docs/DEPLOYMENT.md` - RDS, Secrets Manager, S3, images, release and rollback
- `docs/EVIDENCE_CHECKLIST.md` - how to verify every row of the Phase 1 API & Event Checklist
- `PROGRESS.md` - build status

## Honest status
The code was written and syntax-checked in an environment without Maven or Docker. Run `mvn -B verify` first: if anything
fails to compile or a test fails, fix that before the demo. See `PROGRESS.md` for what is and is not verified.
