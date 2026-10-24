package com.tickethub.payment.service;

import com.tickethub.common.error.BusinessException;
import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.WebhookRecord;
import com.tickethub.payment.dto.Dtos.InternalBooking;
import com.tickethub.payment.dto.Dtos.PaymentResponse;
import com.tickethub.payment.provider.PaymentProvider;
import com.tickethub.payment.repo.PaymentRepository;
import com.tickethub.payment.repo.WebhookRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final WebhookRecordRepository webhooks;
    private final BookingClient bookingClient;
    private final PaymentProvider provider;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, WebhookRecordRepository webhooks, BookingClient bookingClient,
                          PaymentProvider provider, MeterRegistry metrics, PlatformTransactionManager tm) {
        this.payments = payments;
        this.webhooks = webhooks;
        this.bookingClient = bookingClient;
        this.provider = provider;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
    }

    // ------------------------------------------------------------------ initiate

    public PaymentResponse initiate(String bookingId, String customerId) {
        MDC.put("bookingId", bookingId);
        try {
            Payment existing = payments.findByBookingId(bookingId).orElse(null);
            if (existing != null) { // one payment per booking: a repeated request returns it
                return toResponse(owned(existing, customerId));
            }

            InternalBooking booking = bookingClient.getBooking(bookingId);
            if (!booking.customerId().equals(customerId)) { // never reveal other people's bookings
                throw BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found");
            }
            if (!"PENDING_PAYMENT".equals(booking.status()) || !booking.expiresAt().isAfter(Instant.now())) {
                throw BusinessException.conflict("BOOKING_NOT_PAYABLE",
                        "Booking cannot be paid (status: " + booking.status() + ")");
            }

            Payment p = new Payment();
            p.setId(UUID.randomUUID().toString());
            p.setBookingId(bookingId);
            p.setCustomerId(customerId);
            p.setAmount(booking.amount()); // from the booking, never from the client
            p.setCurrency(booking.currency());
            p.setProvider(provider.name());
            p.setStatus(Payment.Status.INITIATED);
            try {
                tx.execute(s -> payments.saveAndFlush(p));
            } catch (DataIntegrityViolationException e) { // concurrent duplicate request
                return toResponse(owned(payments.findByBookingId(bookingId).orElseThrow(), customerId));
            }
            metrics.counter("tickethub.payment.initiated").increment();

            PaymentProvider.Result result;
            try {
                result = provider.initiate(p.getId(), p.getAmount(), p.getCurrency(), bookingId); // never retried
            } catch (RuntimeException e) {
                log.error("Provider call failed for payment {}: {}", p.getId(), e.toString());
                result = new PaymentProvider.Result(PaymentProvider.Status.FAILED, null, truncate("PROVIDER_ERROR: " + e.getMessage()));
            }
            return finish(p.getId(), result.status(), result.reference(), result.reason());
        } finally {
            MDC.remove("bookingId");
        }
    }

    public PaymentResponse get(String id, String customerId) {
        Payment p = payments.findById(id).orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        return toResponse(owned(p, customerId));
    }

    // ------------------------------------------------------------------ results (sync result, webhook, admin simulator)

    /** Applies a provider outcome and tells Booking. Returns the final payment state. */
    private PaymentResponse finish(String paymentId, PaymentProvider.Status status, String reference, String reason) {
        Applied applied = tx.execute(s -> applyInTx(paymentId, status, reference, reason));
        Payment p = applied.payment();
        if (applied.changed() && p.isTerminal()) {
            syncBooking(p);
            p = payments.findById(paymentId).orElse(p);
        }
        return toResponse(p);
    }

    private record Applied(Payment payment, boolean changed) { }

    private Applied applyInTx(String paymentId, PaymentProvider.Status status, String reference, String reason) {
        Payment p = payments.findForUpdate(paymentId)
                .orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (p.isTerminal()) {
            return new Applied(p, false); // late / repeated outcome: ignored (Phase 2 handles conflicting late events)
        }
        if (reference != null && p.getProviderReference() == null) {
            p.setProviderReference(reference);
        }
        switch (status) {
            case SUCCESS -> {
                p.setStatus(Payment.Status.SUCCESS);
                p.setBookingSync(Payment.BookingSync.FAILED_TO_SYNC); // until Booking acknowledges
                metrics.counter("tickethub.payment.success").increment();
            }
            case FAILED -> {
                p.setStatus(Payment.Status.FAILED);
                p.setFailureReason(truncate(reason));
                p.setBookingSync(Payment.BookingSync.FAILED_TO_SYNC);
                metrics.counter("tickethub.payment.failed").increment();
            }
            case PENDING -> p.setStatus(Payment.Status.PENDING);
        }
        return new Applied(p, status != PaymentProvider.Status.PENDING);
    }

    /** Tell Booking the outcome. A failure here is recorded, never thrown to the customer (partial success). */
    private void syncBooking(Payment p) {
        String outcome = p.getStatus() == Payment.Status.SUCCESS ? "SUCCESS" : "FAILED";
        Payment.BookingSync sync;
        try {
            bookingClient.sendPaymentResult(p.getBookingId(), p.getId(), outcome, p.getFailureReason());
            sync = Payment.BookingSync.SYNCED;
        } catch (BusinessException e) {
            if ("BOOKING_NOT_PAYABLE".equals(e.getCode())) {
                sync = Payment.BookingSync.REJECTED_NEEDS_REFUND;
                log.warn("Booking {} rejected payment {} ({}): needs refund/reconciliation (Phase 2)", p.getBookingId(), p.getId(), outcome);
            } else {
                sync = Payment.BookingSync.FAILED_TO_SYNC;
                log.error("Could not report payment {} to booking {}: {}", p.getId(), p.getBookingId(), e.getMessage());
            }
        } catch (RuntimeException e) {
            sync = Payment.BookingSync.FAILED_TO_SYNC;
            log.error("Could not report payment {} to booking {} (will need reconciliation): {}", p.getId(), p.getBookingId(), e.toString());
        }
        final Payment.BookingSync finalSync = sync;
        if (finalSync != Payment.BookingSync.SYNCED) {
            metrics.counter("tickethub.payment.booking.sync.failed").increment();
        }
        tx.executeWithoutResult(s -> payments.findForUpdate(p.getId()).ifPresent(x -> x.setBookingSync(finalSync)));
    }

    // ------------------------------------------------------------------ webhook

    public void handleWebhook(String rawBody, HttpHeaders headers) {
        PaymentProvider.WebhookEvent event = provider.parseWebhook(rawBody, headers); // verifies the signature
        if (event == null) {
            return;
        }
        Applied applied;
        try {
            applied = tx.execute(s -> {
                webhooks.saveAndFlush(new WebhookRecord(event.eventId())); // UNIQUE: duplicate event id fails here
                Payment p = payments.findByProviderReference(event.providerReference()).orElse(null);
                if (p == null) {
                    log.warn("Webhook {} refers to unknown provider reference {}", event.eventId(), event.providerReference());
                    return null;
                }
                return applyInTx(p.getId(), event.status(), null, event.reason());
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate webhook {} ignored", event.eventId());
            return;
        }
        if (applied != null && applied.changed() && applied.payment().isTerminal()) {
            MDC.put("bookingId", applied.payment().getBookingId());
            try {
                syncBooking(applied.payment());
            } finally {
                MDC.remove("bookingId");
            }
        }
    }

    /** Admin/demo helper (simulated provider only): complete a payment that is stuck in PENDING. */
    public PaymentResponse completeManually(String paymentId, PaymentProvider.Status status, String reason) {
        if (status == PaymentProvider.Status.PENDING) {
            throw BusinessException.badRequest("INVALID_OUTCOME", "Outcome must be SUCCESS or FAILED");
        }
        return finish(paymentId, status, null, reason);
    }

    // ------------------------------------------------------------------ helpers

    private Payment owned(Payment p, String customerId) {
        if (!p.getCustomerId().equals(customerId)) {
            throw BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found");
        }
        return p;
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 290 ? s.substring(0, 290) : s);
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                p.getProvider(), p.getProviderReference(), p.getFailureReason(), p.getBookingSync().name(), p.getCreatedAt());
    }
}
