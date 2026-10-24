# Design decisions, trade-offs and known gaps (Phase 1)

## Decisions
| # | Decision | Why | Alternative considered |
|---|---|---|---|
| 1 | **Keycloak** issues JWTs; services are resource servers; roles from `realm_access.roles` (`CUSTOMER`, `ADMIN`) | Matches the Backlog; no user service to build; standard OIDC | Own Auth Service (Checklist rows 1-2) - more code, we own password storage |
| 2 | Gateway validates JWT; **each service validates it again** | One compromised/misrouted path cannot bypass auth | Trust gateway only |
| 3 | **Seat uniqueness enforced by the database**: `seat_reservation UNIQUE(show_id, seat_id)` | Correct under any number of instances; no in-memory locks | Pessimistic lock on show row (serialises all bookings of a show) |
| 4 | Booking owns seat state; Event/Show owns layout; seat map composed by calling Booking | Single owner per fact; no cross-DB reads | Separate Inventory service (extra hop, not needed yet) |
| 5 | Amount = show price x seats, **snapshotted on the booking**; Payment copies it from Booking, never from the client | Prevents price tampering and later price changes altering a booking | Client supplies amount |
| 6 | Booking `expiresAt` + scheduled expiry job; row lock + re-check makes it safe with several instances | Seats are never held forever | TTL cache |
| 7 | Idempotency: `Idempotency-Key` unique per customer on bookings; one payment per booking; webhook events de-duplicated by event id; notifications de-duplicated by event id | Retries and duplicate deliveries are normal | - |
| 8 | Payment -> Booking is a **synchronous internal call** with timeout, bounded retry (safe because Booking treats repeats as no-ops) and circuit breaker; outcome recorded in `payment.booking_sync` | Simple, visible; failure never loses the fact that Booking was not told | Event-driven result (Phase 2 saga) |
| 9 | `/internal/**` guarded by a shared key and never routed by the gateway | Simple service-to-service trust boundary | mTLS / client-credentials tokens (Phase 2 hardening) |
| 10 | Payment provider behind an interface; simulated provider switchable at runtime; Razorpay sandbox implementation | Deterministic failure testing + real integration | Provider SDK in business code |
| 11 | Kafka event published **after** commit, best effort, no outbox | Phase 1 scope | Transactional outbox (Phase 2) |
| 12 | Small `tickethub-common` jar: error format, JWT role mapping, correlation id, internal key filter | Identical behaviour in every service | Copy-paste per service. Trade-off: shared library coupling; it holds no business code |
| 13 | `ddl-auto=update` for schema | Speed for Phase 1 | Flyway migrations (should come first in Phase 2) |

## Known Phase 1 gaps (deliberate or deferred - say these out loud in the demo)
1. **Event lost if Kafka is down at confirmation** - booking stays CONFIRMED, no email. Fix: outbox (Phase 2).
2. **Paid after expiry / after cancel** - Booking refuses (409); payment is marked `REJECTED_NEEDS_REFUND`. Fix: refunds + reconciliation (Phase 2).
3. **Booking not told about the payment** (Booking down) - payment marked `FAILED_TO_SYNC`; booking stays PENDING_PAYMENT until it expires. Fix: reconciliation job (Phase 2).
4. **No automatic SOLD_OUT / COMPLETED show states.**
5. **No rate limiting** at the gateway (needs Redis).
6. **Failed notifications are not retried**; no DLT / replay (Phase 2).
7. `/actuator/**` is open for convenience; restrict at the network level or add auth before real deployment.
8. Keycloak runs in `start-dev` with an imported realm (wildcard redirect URIs, HTTP) - local only.
9. Razorpay provider is written against public docs and was **not run against the live sandbox**.
10. Nothing in this repository has been compiled or executed by the author of this code (see README "Honest status").

## Questions the source documents leave open (resolved here)
- Auth: Keycloak vs Auth Service -> Keycloak (decision 1).
- Where does Notification get the recipient email? -> BookingConfirmed carries `recipientEmail`, taken from the JWT `email` claim when the booking is created (token without email -> 400).
- Payment -> Booking contract -> `POST /internal/bookings/{id}/payment-result` (decision 8).
