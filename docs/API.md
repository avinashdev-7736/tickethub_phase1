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
| POST | `/bookings/{id}/cancel` | owner | only PENDING_PAYMENT; releases seats; repeat is a no-op |

Error codes: `SEAT_UNAVAILABLE` 409, `SHOW_NOT_BOOKABLE` 409, `SHOW_STARTED` 409, `INVALID_SEATS` 400, `DUPLICATE_SEATS` 400,
`TOO_MANY_SEATS` 400, `IDEMPOTENCY_KEY_REUSED` 409, `BOOKING_NOT_FOUND` 404, `BOOKING_NOT_CANCELLABLE` 409, `EMAIL_REQUIRED` 400.

Internal (never routed by gateway; header `X-Internal-Key`): `GET /internal/shows/{showId}/reserved-seats`,
`GET /internal/bookings/{id}`, `POST /internal/bookings/{id}/payment-result {paymentId, status: SUCCESS|FAILED, reason}`.

## Payment Service
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/payments` | owner of booking | body `{bookingId}` only - **no amount**. One payment per booking; repeat returns it |
| GET | `/payments/{id}` | owner | `status`, `bookingSync` (SYNCED / FAILED_TO_SYNC / REJECTED_NEEDS_REFUND) |
| POST | `/payments/webhook` | provider signature | verified HMAC-SHA256; duplicate event ids ignored |
| POST | `/payments/simulator/mode?mode=&delayMs=` | ADMIN | simulated provider only: SUCCESS / FAILURE / RANDOM / PENDING |
| POST | `/payments/simulator/complete?paymentId=&outcome=` | ADMIN | finish a PENDING payment like a callback would |

Simulated webhook: `X-Simulator-Signature: hex(HMAC-SHA256(body, PAYMENT_WEBHOOK_SECRET))`, body
`{"eventId":"e1","providerReference":"sim_<paymentId>","status":"SUCCESS"}`.

## Event (Kafka)
Topic `booking-confirmed` (3 partitions, key = bookingId). Published once, when payment succeeded and the booking became CONFIRMED.
```json
{ "eventId":"uuid", "eventType":"BookingConfirmed", "bookingId":"...", "customerId":"...", "recipientEmail":"a@b.com",
  "showId":1, "eventTitle":"...", "showStartTime":"...", "seats":["A1","A2"], "amount":1000.00, "currency":"INR", "occurredAt":"..." }
```
Consumers must tolerate duplicates (`eventId`). Payment failure, expiry and cancellation publish nothing in Phase 1.

## Status models
- Event: DRAFT -> PUBLISHED -> CANCELLED
- Show: SCHEDULED -> OPEN -> SOLD_OUT / CANCELLED / COMPLETED (SOLD_OUT and COMPLETED are not set automatically in Phase 1)
- Booking: PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED | CANCELLED | EXPIRED
- Seat (per show): AVAILABLE -> RESERVED -> BOOKED (RESERVED/BOOKED are rows in `seat_reservation`; released = row deleted)
- Payment: INITIATED -> PENDING -> SUCCESS | FAILED (CANCELLED / REFUNDED reserved for Phase 2)
- Notification: PENDING -> SENT | FAILED
