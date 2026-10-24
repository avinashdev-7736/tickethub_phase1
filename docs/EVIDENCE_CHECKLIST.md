# Evidence checklist - Phase 1 API & Event Checklist

Maps each row of `TicketHub_Phase1_API_and_Event_Checklist.xlsx` to where it is built and how to prove it.
"Code" = written. Nothing here is "Verified" until you have run it and recorded the evidence.
GW = http://localhost:8080. Get tokens as in the README.

## API checklist
| # | Item | Code | How to verify |
|---|---|---|---|
| 1-2 | Auth register / login | **Keycloak instead of an Auth Service** (decision 1). Register: Keycloak login page (registration enabled). Login: token endpoint | Token request returns JWT with `sub`, `email`, `realm_access.roles` |
| 3 | GET /events | `EventController` | `curl $GW/events` -> paginated `content`, only PUBLISHED |
| 4 | GET /events/{id} | `EventController` | DRAFT event -> 404 for public, 200 for ADMIN |
| 5 | GET /shows | `ShowController` | `curl "$GW/shows?eventId=1"` |
| 6 | GET /shows/{id} | `ShowController` | has `startTime`, `price`, `currency` |
| 7 | GET /shows/{id}/seats | `ShowService.seatMap` + `BookingClient` | statuses AVAILABLE/RESERVED/BOOKED change after book/pay |
| 8 | POST/PUT events | `EventController` | ADMIN token -> 201 DRAFT; CUSTOMER token -> 403 |
| 9 | publish / cancel | `EventService` | DRAFT->PUBLISHED; publish again -> 409 `INVALID_EVENT_STATE`; cancel cancels shows |
| 10 | venues | `VenueController` | create with seats; PUT layout after a show exists -> 409 `VENUE_IN_USE` |
| 11 | POST/PUT shows | `ShowService` | past start -> 400; price/currency stored |
| 12 | open / cancel show | `ShowService` | open DRAFT-event show -> 409 `EVENT_NOT_PUBLISHED` (`ShowServiceTest`) |
| 13 | poster upload | `PosterStorage` | needs S3 (DEPLOYMENT.md section 4) |
| 14 | POST /bookings | `BookingService.create` | 201 PENDING_PAYMENT with `expiresAt`; same `Idempotency-Key` -> 200 same booking (`BookingFlowTest`) |
| 15 | GET /bookings/{id} | `BookingController` | other customer -> 404 |
| 16 | cancel booking | `BookingService.cancel` | seats AVAILABLE again; CONFIRMED -> 409 |
| 17 | reserved-seats (internal) | `InternalBookingController` | via gateway -> 401/403/404 (never routed); with `X-Internal-Key` directly on :8082 -> 200 |
| 18 | payment-result (internal) | `BookingService.applyPaymentResult` | SUCCESS -> CONFIRMED + BOOKED; FAILED -> PAYMENT_FAILED + released; duplicate = no-op |
| 19 | expiry job | `ExpiryJob` | set `booking.expiry-minutes: 1`, book, wait ~1.5 min -> EXPIRED, seats free |
| 20 | POST /payments | `PaymentService.initiate` | amount equals booking amount; foreign/expired booking -> 404/409 (`PaymentFlowTest`) |
| 21 | GET /payments/{id} | `PaymentController` | owner only |
| 22 | webhook | `PaymentService.handleWebhook` | bad signature -> 401; duplicate -> applied once (`PaymentFlowTest`); real sandbox: DEPLOYMENT.md section 5 |
| 23 | Notification consumer | `NotificationService` | email in Mailpit; duplicate event -> one email; SMTP down -> FAILED, booking unaffected (`NotificationFlowTest`) |
| 24 | API Gateway | `platform/api-gateway` | all calls above work through :8080; `/internal/...` -> 401/403 |
| 25 | Service discovery | `platform/discovery-server` | Eureka dashboard lists services; failover profile demo (README) |

## Event
| # | Item | Code | How to verify |
|---|---|---|---|
| 1 | BookingConfirmed | `BookingEventPublisher` -> `BookingConfirmedConsumer` | `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic booking-confirmed --from-beginning`; keyed by bookingId; exactly one message per confirmed booking |

## Cross-cutting
| # | Check | How to verify |
|---|---|---|
| 1 | Protected endpoints need Bearer JWT | `curl -i -X POST $GW/bookings` -> 401 in standard error format |
| 2 | Own bookings/payments only | customer2 reads customer1's booking -> 404 |
| 3 | Catalogue admin-only | CUSTOMER token on `POST /events` -> 403 |
| 4 | Same validation + error format | send an invalid body to each service -> `VALIDATION_FAILED` with `details` |
| 5 | Pagination | `/events?page=0&size=2` |
| 6 | API docs + health | `/swagger-ui.html` and `/actuator/health` on each service |
| 7 | No cross-database reads | each service has its own `*_db` and datasource; grep for other DB names shows none |

## Tests that ship with the code (run `mvn -B verify`)
- `BookingFlowTest`: concurrent booking (exactly one winner), server-side amount, idempotency, failed payment releases seats, duplicate callback publishes once, ownership, cancel
- `PaymentFlowTest`: amount from booking, failure, booking-down sync failure, repeat initiation, foreign/expired booking, signed + duplicate webhook, bad signature
- `NotificationFlowTest`: send, duplicate suppression, SMTP failure recorded, malformed message dropped
- `ShowServiceTest`: show open rules
