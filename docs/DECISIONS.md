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
| 8 | ~~Payment -> Booking synchronous callback~~ **Replaced in Phase 2** by events: Payment publishes `PaymentSucceeded/Failed` (outbox), Booking consumes with an inbox | A callback can fail halfway (payment done, booking never told); events + outbox + reconciliation cannot lose the fact | Orchestrator (see `docs/phase2/SAGA.md`) |
| 9 | `/internal/**` guarded by a shared key and never routed by the gateway | Simple service-to-service trust boundary | mTLS / client-credentials tokens (Phase 2 hardening) |
| 10 | Payment provider behind an interface; simulated provider switchable at runtime; Razorpay sandbox implementation | Deterministic failure testing + real integration | Provider SDK in business code |
| 11 | ~~Best-effort publish after commit~~ **Replaced in Phase 2** by a transactional outbox + relay in Booking and Payment | The business change and its event commit together; a Kafka outage delays an event but cannot lose it | Dual write / CDC |
| 12 | Small `tickethub-common` jar: error format, JWT role mapping, correlation id, internal key filter | Identical behaviour in every service | Copy-paste per service. Trade-off: shared library coupling; it holds no business code |
| 13 | `ddl-auto=update` for schema | Speed for Phase 1 | Flyway migrations (should come first in Phase 2) |

## Phase 2 decisions
| # | Decision | Why |
|---|---|---|
| 14 | **Choreography** (event reactions), with a saga log + `/timeline` for visibility | 3 services, short flow; see `docs/phase2/SAGA.md` for when to switch to orchestration |
| 15 | One topic per producing service (`booking.events`, `payment.events`), key = bookingId, plus `aggregateVersion` | Keeps one booking's events in one partition (order) and lets consumers detect stale events |
| 16 | **At-least-once everywhere + inbox** (unique event id, same transaction as the effect) | Exactly-once delivery does not exist; exactly-once *effect* does |
| 17 | External side effects (email, refund) use an **atomic claim** (`PENDING->SENDING`, `SUCCESS->REFUNDING`) | Two workers can never both send/refund; a crashed claim is retaken after a timeout (we prefer a rare duplicate email over a lost one) |
| 18 | Payment states are **forward-only by rank**; a provider timeout is `UNKNOWN`, never `FAILED` | Late/duplicate/reordered callbacks cannot corrupt state; we never declare a failure we cannot prove |
| 19 | `FAILED -> SUCCESS` is allowed | The provider capturing money is the truth; Booking then rejects + refunds if it can no longer confirm |
| 20 | Reconciliation fixes only what the provider proves; everything else is **FLAGGED** | We never guess about money |
| 21 | Email: non-blocking retry topics + circuit breaker + rate limiter; bad data goes straight to the DLT | One slow/dead provider must not block the stream; bad data can never succeed on retry |
| 22 | Replay = new consumer group + own database + `replay-mode` (no email) | Rebuild state without side effects; limited by topic retention (7 days) |
| 23 | Contract v2: additive field + dual field during a rename (`booking.event.legacy-amount-field`) | Producers and consumers deploy independently |

## Known gaps (after Phase 2)
1. **Razorpay refund / fetch / timeout paths are untested against the live sandbox** (the simulated provider covers the logic). A refund needs the provider payment id from a callback.
2. **No saga timeout**: a payment stuck PENDING for hours is handled by booking expiry (seats) and reconciliation (money), not by an explicit timer.
3. **Dead letters of Booking and Payment** are visible in Kafka UI only (no admin replay API); Notification has one.
4. **A SENDING email whose instance crashed** may be sent twice after the claim timeout (5 minutes) - by choice.
5. **Single broker, replication factor 1** (local). Production needs replication >= 3 and `min.insync.replicas`.
6. **No automatic SOLD_OUT / COMPLETED show states; no gateway rate limiting (needs Redis).**
7. `/actuator/**` is open for convenience; admin APIs are on service ports, not the gateway. Restrict at the network level before real deployment.
8. Keycloak runs in `start-dev` (local only). Schema is managed by `ddl-auto=update`: introduce Flyway before production, and reset volumes (`docker compose down -v`) when upgrading from Phase 1.
9. The Kafka wiring (retry topics, DLTs, groups, relay against a real broker) and the compose profiles are verified only by the drills in `docs/phase2/INCIDENTS.md`, not by automated tests.

## Questions the source documents leave open (resolved here)
- Auth: Keycloak vs Auth Service -> Keycloak (decision 1).
- Where does Notification get the recipient email? -> BookingConfirmed carries `recipientEmail`, taken from the JWT `email` claim when the booking is created (token without email -> 400).
- Payment -> Booking contract -> events `PaymentSucceeded` / `PaymentFailed` (decision 8, Phase 2; the Phase 1 REST callback was removed).
