# Phase 2 - Event catalog, ordering and versioning

## Topics
| Topic | Producer | Consumers (group) | Key | Partitions | Notes |
|---|---|---|---|---|---|
| `booking.events` | Booking (outbox) | Notification (`notification-service`), Payment (`payment-service`) | `bookingId` | 3 | Retention 7 days (`retention.ms`), the limit of any replay |
| `payment.events` | Payment (outbox) | Booking (`booking-service`) | `bookingId` | 3 | Retention 7 days |
| `booking.events.retry.<delay>` | Notification (Spring `@RetryableTopic`) | Notification | `bookingId` | 3 | Delayed, non-blocking email retries (scenario 10) |
| `booking.events.DLT` | Notification | Notification `@DltHandler` -> table `dead_letter` | `bookingId` | 3 | Retries exhausted or bad data (scenario 1) |
| `payment.events.DLT` | Booking error handler | (inspect in Kafka UI) | `bookingId` | 3 | Poison payment events |
| `booking.events.payment-service.DLT` | Payment error handler | (inspect in Kafka UI) | `bookingId` | 3 | Poison booking events seen by Payment |

**Why one topic per producing service (and not one topic per event type):** scenario 6 needs all events of one booking in one partition so they stay in order.
Separate topics per type (for example `booking.confirmed` and `booking.cancelled`) would be separate partitions and could be consumed in any order.
What the guide calls the "booking.confirmed" stream is the `BookingConfirmed` event type on `booking.events`.

## Envelope (every event)
| Field | Meaning |
|---|---|
| `eventId` | UUID. Consumers de-duplicate on it (inbox) |
| `eventType` | `BookingConfirmed`, `BookingRejected`, `BookingCancelled`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded` |
| `schemaVersion` | Contract version (currently 2) |
| `aggregateVersion` | Per-booking (Booking events) / per-payment (Payment events) counter, +1 per event. Lower-than-seen = stale = ignored |
| `occurredAt`, `correlationId` | Time; request correlation id (also sent as the Kafka header `X-Correlation-Id`) |
| `bookingId` | Always present, also the message key |

## Events
| Event | Producer | Payload (besides the envelope) | Reaction |
|---|---|---|---|
| `PaymentSucceeded` | Payment | `paymentId, amount, currency` | Booking: PENDING_PAYMENT -> CONFIRMED + `BookingConfirmed`; otherwise `BookingRejected` |
| `PaymentFailed` | Payment | `paymentId, amount, currency, reason` | Booking: PAYMENT_FAILED, seats released |
| `PaymentRefunded` | Payment | `paymentId, amount, currency, refundId` | Booking: refundState REFUNDED; CANCELLING -> CANCELLED |
| `BookingConfirmed` | Booking | `customerId, recipientEmail, showId, eventTitle, showStartTime, seats[], totalAmount, amount*, currency, seatCategory` | Notification: confirmation email |
| `BookingRejected` | Booking | same + `reason` (`BOOKING_EXPIRED`, `BOOKING_CANCELLED`, `PAYMENT_AFTER_FAILURE`) | Payment: refund. Notification: "could not be confirmed" email |
| `BookingCancelled` | Booking | same + `reason` (`CUSTOMER_REQUEST`) | Payment: refund. Notification: cancellation email |

`*` `amount` is the deprecated v1 name, still sent while consumers migrate (see below).

## Ordering and duplicates
- **Key = bookingId** -> one partition per booking -> events of a booking are consumed in the order they were written.
- Ordering can still break: retry topics, replays, redelivery. So every consumer also checks `aggregateVersion` and **ignores an event whose version is not higher than the last one applied**. Example: `BookingCancelled` (v2) overtakes `BookingConfirmed` (v1) -> the confirmed email is never sent after the cancelled one.
- **At-least-once delivery** is assumed everywhere (outbox relay may publish twice, Kafka may redeliver). Every consumer has an **inbox** with a unique event id written in the same transaction as the effect.
  Where the effect is an external call (email, refund), an atomic **claim** (`PENDING -> SENDING`, `SUCCESS -> REFUNDING`) lets exactly one worker perform it.

## Contract change (scenario 13): v1 -> v2
- v1 `BookingConfirmed`: `amount`.
- v2 **adds** `seatCategory` (safe: old consumers ignore unknown fields) and **renames** `amount` -> `totalAmount` (breaking for old consumers).
- Safe migration used here: 1) producer sends BOTH `amount` and `totalAmount` (`booking.event.legacy-amount-field=true`). 2) Notification reads `totalAmount`, falls back to `amount`, ignores unknown fields (tolerant reader; test `contractV1AndV2BothParse`).
  3) When every consumer has been deployed with the new reader, set `booking.event.legacy-amount-field=false`. 4) Events written before the change stay in the topic (7 days) and are still readable because of the fallback.
- Rule of thumb: **adding** a field is free; **renaming/removing/retyping** needs a migration window like the one above.
