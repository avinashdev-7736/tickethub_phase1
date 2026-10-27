# TicketHub Phase 1 - API reference

All client calls go through the gateway (`http://localhost:8080`). Send `Authorization: Bearer <JWT>` where marked.
Every response carries `X-Correlation-Id` (send your own to trace a request end to end).

## Error format (all services)
```json
{ "timestamp": "2026-10-24T09:30:00Z", "status": 409, "error": "Conflict", "code": "SEAT_UNAVAILABLE",
  "message": "One or more selected seats are no longer available", "path": "/bookings",
  "correlationId": "7b0e...", "details": null }
```
`details` lists field errors for `VALIDATION_FAILED` (400). 401 = no/invalid token, 403 = wrong role, 404 = not found **or not yours**.

## Event/Show Service
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/events?category=&q=&page=&size=` | public | PUBLISHED only, paginated (size max 50) |
| GET | `/events/{id}` | public | DRAFT/CANCELLED visible to ADMIN only; `posterUrl` is a 15-min signed URL |
| GET | `/shows?eventId=&from=&to=&page=&size=` | public | OPEN / SOLD_OUT, future, event PUBLISHED |
| GET | `/shows/{id}` | public | time, `price`, `currency`, status |
| GET | `/shows/{showId}/seats` | public | each seat AVAILABLE / RESERVED / BOOKED; `503 SEAT_STATE_UNAVAILABLE` if Booking is down |
| POST/PUT | `/events`, `/events/{id}` | ADMIN | created as DRAFT |
| POST | `/events/{id}/publish`, `/events/{id}/cancel` | ADMIN | DRAFT->PUBLISHED; cancel also cancels its shows |
| POST | `/events/{id}/poster` (multipart `file`) | ADMIN | jpeg/png/webp, max 2 MB, private S3 |
| POST/PUT/GET | `/venues`, `/venues/{id}` | ADMIN | seat layout `{row, number, type}`; layout locked once shows exist |
| POST/PUT | `/shows`, `/shows/{id}` | ADMIN | `{eventId, venueId, startTime, endTime, price, currency}`; edit only while SCHEDULED |
| POST | `/shows/{id}/open`, `/shows/{id}/cancel` | ADMIN | open needs PUBLISHED event + future start |

## Booking Service
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/bookings` | CUSTOMER | body `{showId, seatIds[]}`, optional `Idempotency-Key` header. 201 new, **200 replay of same key**. Amount = price x seats (server side). PENDING_PAYMENT with `expiresAt` |
| GET | `/bookings/{id}` | owner | status, seats, amount, expiry |
| POST | `/bookings/{id}/cancel` | owner | PENDING_PAYMENT: cancelled at once. CONFIRMED (show not started): seats freed at once, status `CANCELLING`, refund requested, `CANCELLED` when refunded. Repeat is a no-op |
| GET | `/bookings/{id}/timeline` | owner or ADMIN | **Phase 2.** Every state change with reason and event id: "what state is it in and why?" |

Error codes: `SEAT_UNAVAILABLE` 409, `SHOW_NOT_BOOKABLE` 409, `SHOW_STARTED` 409, `INVALID_SEATS` 400, `DUPLICATE_SEATS` 400,
`TOO_MANY_SEATS` 400, `IDEMPOTENCY_KEY_REUSED` 409, `BOOKING_NOT_FOUND` 404, `BOOKING_NOT_CANCELLABLE` 409, `EMAIL_REQUIRED` 400.

Internal (never routed by gateway; header `X-Internal-Key`): `GET /internal/shows/{showId}/reserved-seats`,
`GET /internal/bookings/{id}`, `GET /internal/bookings/confirmed?since=<ISO instant>` (reconciliation).
**Phase 2:** the REST callback `POST /internal/bookings/{id}/payment-result` was removed. Payment tells Booking through events (`PaymentSucceeded` / `PaymentFailed`) - see `docs/phase2/EVENTS.md`.

## Payment Service
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/payments` | owner of booking | body `{bookingId}` only - **no amount**. One payment per booking; repeat returns it |
| GET | `/payments/{id}` | owner or ADMIN | `status` (INITIATED, PENDING, UNKNOWN, AUTHORIZED, FAILED, SUCCESS, REFUNDING, REFUNDED), `refundId` |
| POST | `/payments/{id}/resolve` | owner or ADMIN | **Phase 2.** For UNKNOWN / PENDING / AUTHORIZED: ask the provider what really happened and apply it |
| POST | `/payments/webhook` | provider signature | verified HMAC-SHA256; duplicate event ids ignored; only forward state moves are applied (late/reordered callbacks are ignored); unknown payment -> 404 so the provider retries |
| POST | `/payments/simulator/mode?mode=&delayMs=` | ADMIN | simulated provider only: SUCCESS / FAILURE / RANDOM / PENDING / TIMEOUT_CAPTURED / TIMEOUT_NOT_CAPTURED |
| POST | `/payments/simulator/complete?paymentId=&outcome=` | ADMIN | finish a stuck payment like a callback would (SUCCESS / FAILED / AUTHORIZED) |
| POST | `/payments/simulator/silent-capture?paymentId=` | ADMIN | provider captured the money but sends NO callback (reconciliation demo) |

Simulated webhook: `X-Simulator-Signature: hex(HMAC-SHA256(body, PAYMENT_WEBHOOK_SECRET))`, body
`{"eventId":"e1","providerReference":"sim_<paymentId>","status":"SUCCESS"}`.

## Events (Kafka) - Phase 2
Topics `booking.events` (BookingConfirmed / BookingRejected / BookingCancelled) and `payment.events` (PaymentSucceeded / PaymentFailed / PaymentRefunded), key = `bookingId`.
Full catalog, envelope, ordering rules and the v1 -> v2 contract migration: **`docs/phase2/EVENTS.md`**.
All events are published through a transactional outbox (never lost, possibly delivered twice) and consumed with an inbox (duplicates ignored).

## Admin APIs (ADMIN token, direct service ports - not routed by the gateway)
| Service | Method | Path | Purpose |
|---|---|---|---|
| Notification :8084 | GET | `/admin/dead-letters` | dead-lettered messages |
| Notification :8084 | POST | `/admin/dead-letters/{id}/replay` | send one back; optional body `{"payload":"..."}` with a corrected payload |
| Payment :8083 | POST | `/admin/reconciliation/run` | run reconciliation now, returns fixed / flagged findings |
| Payment :8083 | GET | `/admin/reconciliation/findings` | recent findings |
| Payment :8083 | GET / POST | `/admin/outbox`, `/admin/outbox/relay` | pending outbox events / publish now |

## Status models
- Event: DRAFT -> PUBLISHED -> CANCELLED
- Show: SCHEDULED -> OPEN -> SOLD_OUT / CANCELLED / COMPLETED (SOLD_OUT and COMPLETED are not set automatically in Phase 1)
- Booking: PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED | CANCELLED | EXPIRED; paid too late -> REJECTED; cancelled after paying -> CANCELLING -> CANCELLED. `refundState`: NONE / PENDING / REFUNDED
- Seat (per show): AVAILABLE -> RESERVED -> BOOKED (RESERVED/BOOKED are rows in `seat_reservation`; released = row deleted)
- Payment (forward only, by rank): INITIATED < PENDING = UNKNOWN < AUTHORIZED < FAILED < SUCCESS < REFUNDING < REFUNDED
- Notification: PENDING -> SENDING -> SENT | RETRYING | FAILED | REPLAYED
