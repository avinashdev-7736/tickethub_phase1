# Incident notes (scenario 14)

How to use this file: run each drill (`.\scripts\phase2\chaos.ps1 <drill>`), look at Kafka UI / Grafana / Jaeger / Kibana (search `bookingId`), and record actual results in **Observed**.
"Expected" below is what the design should produce. If observations differ, write down the discrepancy and fix it.

Template: Trigger - Symptom - Where to look - Cause - Fix - Prevention - Observed.

---
## 1. Kafka is down while customers pay (`kafka-down`)
- **Trigger:** `docker compose stop kafka`, book and pay, then `docker compose start kafka`.
- **Expected symptom:** the payment answers SUCCESS, the booking stays PENDING_PAYMENT for a while; no event is lost.
- **Where to look:** `GET :8083/admin/outbox` (pending > 0), alert `OutboxBacklog`, Grafana "Outbox" panel, `tickethub_outbox_publish_failed_total`.
- **Cause:** the broker is unreachable; events wait in the payment outbox.
- **Fix:** start Kafka. The relay publishes in order, Booking confirms, one email is sent.
- **Prevention:** transactional outbox; at-least-once + inbox; the relay stops at the first failure to keep order.
- **Observed:** ________

## 2. Email provider is down (`mail-down`)
- **Trigger:** `docker compose stop mailpit`, book and pay, wait, `docker compose start mailpit`.
- **Expected symptom:** booking CONFIRMED immediately; notification RETRYING; after recovery exactly one email.
- **Where to look:** `notification.status = RETRYING` in `notification_db`, topics `booking.events.retry.*` in Kafka UI, `/actuator/circuitbreakers`, alert `EmailProviderCircuitOpen`.
- **Cause:** SMTP unreachable; circuit breaker opens after 3 failures so the dead provider is not hammered.
- **Fix:** restore the provider; retries (2s, 6s, 18s) or a DLT replay deliver the mail.
- **Prevention:** email failure never touches the booking; non-blocking retry topics; atomic claim = one send; inbox = no duplicate.
- **Observed:** ________

## 3. Duplicate and reordered provider callbacks (`duplicate-webhook`)
- **Trigger:** the drill sends SUCCESS twice (same event id) then a late AUTHORIZED and a late FAILED.
- **Expected symptom:** nothing visible: booking CONFIRMED once, one email, timeline shows a single confirmation.
- **Where to look:** `webhook_record` (duplicate rejected), logs "ignored (already SUCCESS)", metric `tickethub_payment_transition_ignored_total`.
- **Cause:** providers retry and reorder callbacks.
- **Fix:** none needed.
- **Prevention:** webhook event-id dedupe + forward-only state machine.
- **Observed:** Passed on 2026-10-28. Booking `1a4afb4e-15a7-4ce8-a88e-31b962702fbd` reached CONFIRMED once after a duplicate SUCCESS and late AUTHORIZED/FAILED callbacks; its timeline has one confirmation and Mailpit recorded exactly one new confirmation email.

## 4. Payment arrives after the booking was cancelled (`late-payment`)
- **Trigger:** payment stuck PENDING, booking cancelled, provider then reports SUCCESS.
- **Expected symptom:** booking REJECTED with refundState PENDING then REFUNDED; customer gets the "could not be confirmed" email.
- **Where to look:** `GET /bookings/{id}/timeline`, topic `booking.events` (BookingRejected, reason BOOKING_CANCELLED), then `payment.events` (PaymentRefunded).
- **Cause:** money and booking state raced.
- **Fix:** automatic compensation.
- **Prevention:** `BookingRejected` -> idempotent refund -> `PaymentRefunded`.
- **Observed:** ________

## 5. Customer cancels after paying (`cancel-after-pay`)
- **Expected symptom:** seats free at once, booking CANCELLING, then CANCELLED with refundState REFUNDED; cancellation email.
- **Prevention:** `BookingCancelled` -> refund; seats are released first so the customer-visible action is instant.
- **Observed:** Passed on 2026-10-28. Booking `d93a9fd6-1aa4-4661-9b1a-2c74d1770ac7` moved CONFIRMED -> CANCELLING -> CANCELLED with refundState REFUNDED; Mailpit received confirmation and cancellation emails, and seat A2 was AVAILABLE afterward.

## 6. The provider captured money but no callback ever came (`silent-capture`)
- **Trigger:** `POST /payments/simulator/silent-capture`, then `POST :8083/admin/reconciliation/run`.
- **Expected symptom:** booking stuck PENDING_PAYMENT until reconciliation (or its schedule) runs; then CONFIRMED.
- **Where to look:** reconciliation report (`UNRESOLVED_RESOLVED`, FIXED), alert `ReconciliationFlagged` for anything FLAGGED.
- **Cause:** callback lost or never sent.
- **Fix:** reconciliation asks the provider (source of truth) and applies the answer.
- **Prevention:** scheduled reconciliation every 5 minutes; never guess: anything unprovable is FLAGGED.
- **Observed:** ________

## 7. A service restarts in the middle of a saga (`restart-booking`)
- **Expected symptom:** booking CONFIRMED once after the restart; no duplicate emails.
- **Cause:** the payment event waited in Kafka; consumer offsets are only committed after processing; the inbox absorbs a redelivery.
- **Observed:** ________

## 8. Poison message (`poison`)
- See KAFKA_RUNBOOK.md, scenario 1. **Expected:** 2 dead letters, the good message after them still processed.
- **Observed:** ________

---
### Post-incident checklist (copy for each real incident)
1. What did the customer see? 2. Which booking ids? 3. Timeline + correlation id -> which services were involved? 4. Was money affected? (reconciliation report) 5. What is the one change that makes it not happen again?
