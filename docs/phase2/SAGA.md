# Phase 2 - The booking saga

## What the saga is
A booking spans three services and no distributed transaction, so consistency comes from events and compensation.

```
 customer            Booking                    Payment                      Notification
   | POST /bookings    |                          |                              |
   |------------------>| PENDING_PAYMENT          |                              |
   | POST /payments    |                          |                              |
   |---------------------------------------------->| SUCCESS  --PaymentSucceeded-->|(Booking consumes)
   |                   | CONFIRMED --BookingConfirmed------------------------------>| email
   |                   |                          |                              |
   |  happy path ends. Compensation paths:                                       |
   |                   |<--PaymentSucceeded (too late: expired / cancelled)       |
   |                   | REJECTED --BookingRejected-->| refund (exactly once)    |
   |                   |<-----------PaymentRefunded---| REFUNDED                  |
   | cancel a CONFIRMED booking                                                  |
   |------------------>| CANCELLING --BookingCancelled-->| refund               |
   |                   |<-----------PaymentRefunded---| REFUNDED                  |
   |                   | CANCELLED (refundState REFUNDED)                         |
```

## Booking statuses
`PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED | CANCELLED | EXPIRED`;
paid too late -> `REJECTED` (refundState PENDING -> REFUNDED);
customer cancels a confirmed booking -> `CANCELLING` -> `CANCELLED` once the refund is done.

Payment statuses (forward only, by rank): `INITIATED(0) < PENDING = UNKNOWN(1) < AUTHORIZED(2) < FAILED(3) < SUCCESS(4) < REFUNDING(5) < REFUNDED(6)`.
A change is applied only if the new rank is higher, so duplicate, late and reordered callbacks cannot move a payment backwards.
`FAILED -> SUCCESS` is allowed on purpose: a provider can report a failed attempt and then capture a retry for the same order; the money is the truth, and Booking will reject the booking and refund if it can no longer be confirmed.

## Compensation table
| Situation | Who notices | Compensation |
|---|---|---|
| Payment succeeded after the booking expired | Booking (on `PaymentSucceeded`) | `BookingRejected` -> Payment refunds |
| Payment succeeded after the customer cancelled | Booking | same |
| Customer cancels a CONFIRMED booking | Booking | seats freed immediately, `BookingCancelled` -> refund |
| Payment failed | Booking (on `PaymentFailed`) | release seats (nothing to refund) |
| Refund call fails | Payment | claim released, error thrown, Kafka redelivers with backoff; after the retries -> DLT |
| Booking never heard about a successful payment | Reconciliation | event published again |
| Customer paid for a dead booking and no event arrived | Reconciliation | refund |
| CONFIRMED booking with no payment | Reconciliation | **flagged for a human** (we never guess about money) |

## "What state is booking X in, and why?" (scenario 7)
- `GET /bookings/{id}/timeline` (owner or ADMIN) returns the status, refund state, both version counters and every transition with its reason and the id of the event it caused.
- Logs carry `bookingId` and the correlation id (also across Kafka, through the `X-Correlation-Id` header); traces show the REST part; the timeline shows the asynchronous part.

## Decision: choreography (kept) vs orchestration (prototype notes)
**Chosen: choreography.** Booking, Payment and Notification each react to events.
| | Choreography (what we built) | Orchestration (a "saga orchestrator" service or a state machine owning the flow) |
|---|---|---|
| Coupling | Services know event names only | Orchestrator knows every service's commands |
| Where is the flow written down? | Spread over consumers (we compensate for that with the timeline + this document) | One place, easy to read and change |
| Failure handling | Each service owns its own retries and compensation | Orchestrator drives retries/timeouts centrally |
| Adding a step (e.g. loyalty points) | Add a consumer, change nothing else | Change the orchestrator |
| Risk | Hard to see the whole flow; cyclic event chains | The orchestrator becomes a god service / single point of failure |
| Fit for TicketHub today | **Good**: 3 services, a short linear flow with 2 compensations | Worth it when the flow gets 5+ steps, parallel branches or long timeouts |

Why not orchestration now: the flow is short, every step already has an owner with its own database, and the saga log gives most of the visibility an orchestrator would add.
**Switch when:** a new step needs coordination across services (inventory hold + payment + loyalty + invoice), or support keeps asking "where is this booking stuck?" and the timeline is not enough.

## Known limits of this design
- The refund of a payment that exists only at the provider (callback never arrived, provider payment id unknown) cannot be done by the Razorpay implementation until the payment id is learned; it throws and is retried/dead-lettered.
- Timeouts of the saga as a whole (a payment that stays PENDING forever) are handled by booking expiry (seats) and reconciliation (money), not by an explicit saga timeout.
- No partial refunds; one payment per booking.
