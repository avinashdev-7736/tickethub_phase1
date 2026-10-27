# Phase 2 - make it reliable (what was built and how to prove it)

Source: *TicketHub Phase 2 Student Guide*. Reading order: this file -> `EVENTS.md` -> `SAGA.md` -> `KAFKA_RUNBOOK.md` -> `INCIDENTS.md`.
Start from a clean database after upgrading from Phase 1 (`docker compose down -v`): tables changed (new columns, new tables, new Booking/Payment states).

| # | Scenario | Where it lives | How to prove it |
|---|---|---|---|
| 0 | Look inside Kafka | `kafka-ui` in compose (http://localhost:8085), `KAFKA_RUNBOOK.md` | Open the UI, describe `booking.events` and the group lag |
| 1 | Poison messages -> DLT | Notification `BookingEventsConsumer` (`@RetryableTopic`, `@DltHandler`), `DeadLetterService`, `/admin/dead-letters`; Booking/Payment `KafkaConfig` error handlers | `scripts/phase2/poison.ps1`; unit test `malformedAndIncompleteMessagesAreInvalidNotRetried` |
| 2 | Duplicates -> inbox + claim | Notification `InboxEvent`, `NotificationRepository.claim`; Booking/Payment inbox | tests `sameEventDeliveredToManyThreadsAtOnceSendsExactlyOneEmail`, `duplicateEventIsNotSentTwice`, `duplicatePaymentEventIsAppliedOnce` |
| 3 | Event lost on Kafka outage -> outbox | Booking `OutboxService/OutboxRelay`, Payment same | test `confirmedEventSurvivesAKafkaOutageAndIsPublishedInOrderAfterwards`; drill `kafka-down` |
| 4 | Replace the REST callback -> saga | Payment publishes `PaymentSucceeded/Failed`; Booking `BookingSagaService` | tests `paymentSucceededConfirmsBookingAndWritesBookingConfirmedToTheOutbox`, `successfulPaymentUsesBookingAmount...` |
| 5 | Compensation: refunds | Booking `REJECTED/CANCELLING`; Payment `RefundService` | tests `paymentAfterExpiryRejectsTheBookingAndRequestsARefund`, `rejectedBookingIsRefundedOnceEvenIfTheEventArrivesManyTimes`, `parallelRefundRequestsRefundExactlyOnce`, `failedRefundReleasesTheClaimAndTheRetrySucceeds` |
| 6 | Ordering | key = bookingId; `aggregateVersion` | tests `staleEventArrivingAfterNewerOneIsIgnored`, `staleAndReplayedPaymentEventsAreIgnored` |
| 7 | Saga visibility + decision | `GET /bookings/{id}/timeline`, `SAGA.md` | test `timelineExplainsWhatHappenedAndWhy` |
| 8 | Reconciliation | Payment `ReconciliationService`, `POST :8083/admin/reconciliation/run` | tests `reconciliation*`; drill `silent-capture` |
| 9 | Provider callbacks, UNKNOWN | Payment forward-only `Status` ranks, `ProviderTimeoutException`, `/payments/{id}/resolve` | tests `duplicateAndReorderedSignedWebhooksNeverMovePaymentBackwards`, `timeoutWithMoneyCapturedBecomesUnknownThenSuccessWhenResolved` |
| 10 | Email provider failures | `MailGateway` (circuit breaker + rate limiter), retry topics, `RETRYING` | tests `providerDownMarksRetryingThenSucceedsExactlyOnceOnRetry`, `circuitBreakerOpensSoTheDeadProviderIsNotHammered`; drill `mail-down` |
| 11 | Consumer lag | `NOTIFICATION_SIMULATED_DELAY_MS`, burst scripts, `--profile scale`, Grafana panel, alert `ConsumerLagHigh` | `KAFKA_RUNBOOK.md` scenario 11 (needs a running stack) |
| 12 | Replay | `notification.replay-mode`, `--profile replay`, new group, own DB | test `replayRebuildsRecordsWithoutSendingEmail`; runbook scenario 12 |
| 13 | Contract change | `BookingEvents` (v2 + legacy `amount`), tolerant `BookingEvent` | test `contractV1AndV2BothParse`; `EVENTS.md` |
| 14 | Chaos + incidents | `scripts/phase2/chaos.ps1`, `INCIDENTS.md` | run the drills and fill in **Observed** |

## Observability baseline (guide, "before you start")
- **Booking id in logs:** MDC `bookingId` in every saga/refund/notification step (log pattern and JSON logs already carry it).
- **Correlation across Kafka:** outbox stores the correlation id -> Kafka header `X-Correlation-Id` -> consumer MDC; Boot's Kafka observation (`spring.kafka.*.observation-enabled`) propagates the trace context so Jaeger shows the async hop.
- **Metrics:** outbox pending/published/failed, saga stale/duplicate, refund completed/failed, notification sent/retrying/deadlettered/duplicate/stale/replayed, reconciliation fixed/flagged, Kafka consumer lag (Micrometer).
- **Dashboard + alerts:** Grafana "TicketHub Overview" (new Phase 2 panels), Prometheus `alerts.yml`.

## Honest status
Code compiles only in your environment: run `mvn -B verify` first. The unit/integration tests use H2 and mocks. **Not covered by automated tests (run the drills):**
the Kafka wiring itself (`@RetryableTopic` retry/DLT topics, the `DefaultErrorHandler` DLTs, consumer groups, partitions, the outbox relay against a real broker), the compose profiles, the Grafana/Prometheus rules, and the Razorpay refund/fetch calls.
