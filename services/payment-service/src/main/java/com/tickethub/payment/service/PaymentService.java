package com.tickethub.payment.service;

import com.tickethub.common.error.BusinessException;
import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.WebhookRecord;
import com.tickethub.payment.dto.Dtos.InternalBooking;
import com.tickethub.payment.dto.Dtos.PaymentResponse;
import com.tickethub.payment.provider.PaymentProvider;
import com.tickethub.payment.provider.ProviderTimeoutException;
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

/**
 * Payments. Every state change and the event that announces it commit in ONE transaction (outbox); nothing calls Booking directly.
 * The state machine only moves forward, so duplicated, late or out-of-order provider callbacks are harmless.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final WebhookRecordRepository webhooks;
    private final BookingClient bookingClient;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, WebhookRecordRepository webhooks, BookingClient bookingClient,
                          PaymentProvider provider, PaymentEvents events, MeterRegistry metrics, PlatformTransactionManager tm) {
        this.payments = payments;
        this.webhooks = webhooks;
        this.bookingClient = bookingClient;
        this.provider = provider;
        this.events = events;
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
                throw BusinessException.conflict("BOOKING_NOT_PAYABLE", "Booking cannot be paid (status: " + booking.status() + ")");
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
            } catch (ProviderTimeoutException e) {
                // No answer is NOT a failure: the money may have moved. Park it as UNKNOWN and resolve it with the provider later.
                log.warn("Provider timed out for payment {}: status is UNKNOWN until resolved", p.getId());
                metrics.counter("tickethub.payment.unknown").increment();
                result = new PaymentProvider.Result(PaymentProvider.Status.UNKNOWN, e.getReference(), "PROVIDER_TIMEOUT");
            } catch (RuntimeException e) {
                log.error("Provider call failed for payment {}: {}", p.getId(), e.toString());
                result = new PaymentProvider.Result(PaymentProvider.Status.FAILED, null, truncate("PROVIDER_ERROR: " + e.getMessage()));
            }
            return toResponse(apply(p.getId(), result.status(), result.reference(), null, result.reason()));
        } finally {
            MDC.remove("bookingId");
        }
    }

    public PaymentResponse get(String id, String customerId, boolean admin) {
        Payment p = payments.findById(id).orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        return toResponse(admin ? p : owned(p, customerId));
    }

    /** Ask the provider what really happened and apply it (for UNKNOWN / PENDING / AUTHORIZED payments). Owner or ADMIN. */
    public PaymentResponse resolve(String id, String customerId, boolean admin) {
        Payment p = payments.findById(id).orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (!admin) {
            owned(p, customerId);
        }
        return toResponse(resolveWithProvider(p));
    }

    /** Used by resolve() and by reconciliation. Applies the provider's answer through the forward-only state machine. */
    public Payment resolveWithProvider(Payment p) {
        if (p.getProviderReference() == null) {
            log.warn("Payment {} has no provider reference; cannot be resolved by lookup", p.getId());
            return p;
        }
        PaymentProvider.Result truth = provider.fetch(p.getProviderReference());
        return apply(p.getId(), truth.status(), null, null, truth.reason());
    }

    // ------------------------------------------------------------------ the state machine

    /** Applies an outcome in its own transaction. */
    Payment apply(String paymentId, PaymentProvider.Status status, String reference, String providerPaymentId, String reason) {
        return tx.execute(s -> applyInTx(paymentId, status, reference, providerPaymentId, reason));
    }

    /**
     * Forward-only transition + the event announcing it, same transaction.
     * A new status is applied only if its rank is HIGHER than the current one; otherwise it is ignored (duplicate, late, reordered).
     */
    Payment applyInTx(String paymentId, PaymentProvider.Status status, String reference, String providerPaymentId, String reason) {
        Payment p = payments.findForUpdate(paymentId)
                .orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (reference != null && p.getProviderReference() == null) {
            p.setProviderReference(reference);
        }
        if (providerPaymentId != null && p.getProviderPaymentId() == null) {
            p.setProviderPaymentId(providerPaymentId);
        }
        Payment.Status target = switch (status) {
            case SUCCESS -> Payment.Status.SUCCESS;
            case FAILED -> Payment.Status.FAILED;
            case AUTHORIZED -> Payment.Status.AUTHORIZED;
            case PENDING -> Payment.Status.PENDING;
            case UNKNOWN -> Payment.Status.UNKNOWN;
        };
        if (target.rank() <= p.getStatus().rank()) {
            metrics.counter("tickethub.payment.transition.ignored").increment();
            log.info("Payment {}: {} ignored (already {})", p.getId(), target, p.getStatus());
            return p;
        }
        p.setStatus(target);
        if (target == Payment.Status.SUCCESS) {
            metrics.counter("tickethub.payment.success").increment();
            events.succeeded(p);
        } else if (target == Payment.Status.FAILED) {
            p.setFailureReason(truncate(reason));
            metrics.counter("tickethub.payment.failed").increment();
            events.failed(p, truncate(reason));
        }
        return p;
    }

    // ------------------------------------------------------------------ webhook

    public void handleWebhook(String rawBody, HttpHeaders headers) {
        PaymentProvider.WebhookEvent event = provider.parseWebhook(rawBody, headers); // verifies the signature
        if (event == null) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> {
                webhooks.saveAndFlush(new WebhookRecord(event.eventId())); // UNIQUE: a duplicate event id fails here
                Payment p = payments.findByProviderReference(event.providerReference())
                        .orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND",
                                "No payment for provider reference (the provider will retry)"));
                applyInTx(p.getId(), event.status(), null, event.providerPaymentId(), event.reason());
            });
        } catch (DataIntegrityViolationException e) {
            if (webhooks.existsByEventId(event.eventId())) {
                log.info("Duplicate webhook {} ignored", event.eventId());
                return;
            }
            throw e;
        }
    }

    /** Admin/demo helper (simulated provider only): complete a payment that is stuck, like a provider callback would. */
    public PaymentResponse completeManually(String paymentId, PaymentProvider.Status status, String reason) {
        if (status == PaymentProvider.Status.PENDING || status == PaymentProvider.Status.UNKNOWN) {
            throw BusinessException.badRequest("INVALID_OUTCOME", "Outcome must be SUCCESS, FAILED or AUTHORIZED");
        }
        return toResponse(apply(paymentId, status, null, null, reason));
    }

    public String referenceOf(String paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Payment not found")).getProviderReference();
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

    PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                p.getProvider(), p.getProviderReference(), p.getFailureReason(), p.getRefundId(), p.getCreatedAt());
    }
}
