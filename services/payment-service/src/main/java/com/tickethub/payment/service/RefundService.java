package com.tickethub.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.messaging.InvalidEventException;
import com.tickethub.common.messaging.MessagingMdc;
import com.tickethub.common.messaging.TransientProcessingException;
import com.tickethub.payment.domain.InboxEvent;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.provider.PaymentProvider;
import com.tickethub.payment.repo.InboxEventRepository;
import com.tickethub.payment.repo.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compensation: Booking says "give the money back" (BookingRejected / BookingCancelled) and Payment refunds, exactly once.
 * Exactly-once comes from the payment's own state: SUCCESS -> REFUNDING (claimed under a row lock) -> REFUNDED.
 * A second event, a redelivery, a parallel worker or reconciliation all find REFUNDING/REFUNDED and do nothing.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;
    private final InboxEventRepository inbox;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public RefundService(PaymentRepository payments, InboxEventRepository inbox, PaymentProvider provider, PaymentEvents events,
                         ObjectMapper mapper, MeterRegistry metrics, PlatformTransactionManager tm) {
        this.payments = payments;
        this.inbox = inbox;
        this.provider = provider;
        this.events = events;
        this.mapper = mapper;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
    }

    public void onBookingEvent(String payload) {
        BookingEvent event = parse(payload);
        if (!BookingEvent.REJECTED.equals(event.eventType()) && !BookingEvent.CANCELLED.equals(event.eventType())) {
            return; // BookingConfirmed etc. are not our business
        }
        MessagingMdc.run(null, event.bookingId(), () -> refundFor(event.bookingId(), event.eventId(), event.eventType()));
    }

    private BookingEvent parse(String payload) {
        try {
            BookingEvent e = mapper.readValue(payload, BookingEvent.class);
            if (e == null || e.eventId() == null || e.eventId().isBlank() || e.bookingId() == null || e.bookingId().isBlank()) {
                throw new InvalidEventException("Booking event without eventId or bookingId");
            }
            return e;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new InvalidEventException("Unreadable booking event: " + ex.getMessage(), ex);
        }
    }

    /**
     * @return true if THIS call completed the refund. Throws {@link TransientProcessingException} if the provider failed
     *         (the claim is released so the redelivery can try again).
     */
    public boolean refundFor(String bookingId, String eventId, String cause) {
        if (inbox.existsByEventId(eventId)) {
            metrics.counter("tickethub.refund.duplicate").increment();
            log.info("Event {} already processed", eventId);
            return false;
        }
        Payment claimed;
        try {
            claimed = tx.execute(s -> claim(bookingId, eventId));
        } catch (DataIntegrityViolationException e) { // two workers recorded the same event id
            return false;
        }
        if (claimed == null) {
            return false;
        }
        String refundId = callProvider(claimed, eventId);
        tx.executeWithoutResult(s -> {
            Payment p = payments.findForUpdate(claimed.getId()).orElseThrow();
            p.setStatus(Payment.Status.REFUNDED);
            p.setRefundId(refundId);
            events.refunded(p); // PaymentRefunded, in the same transaction as the state change
            inbox.save(new InboxEvent(eventId, cause));
        });
        metrics.counter("tickethub.refund.completed").increment();
        log.info("Payment {} refunded ({}) because of {}", claimed.getId(), refundId, cause);
        return true;
    }

    /** Under a row lock: SUCCESS -> REFUNDING means this caller owns the refund. Anything else means nothing to do. */
    private Payment claim(String bookingId, String eventId) {
        Payment p = payments.findForUpdateByBookingId(bookingId).orElse(null);
        if (p == null) {
            log.warn("No payment for booking {}: nothing to refund", bookingId);
            inbox.save(new InboxEvent(eventId, "no-payment"));
            return null;
        }
        if (p.getStatus() == Payment.Status.SUCCESS) {
            p.setStatus(Payment.Status.REFUNDING);
            return p;
        }
        if (p.getStatus() == Payment.Status.REFUNDING) {
            log.info("Payment {} is already being refunded", p.getId());
            return null; // not recorded in the inbox: if that worker died, reconciliation flags it
        }
        log.warn("Payment {} is {}: no captured money to refund", p.getId(), p.getStatus());
        inbox.save(new InboxEvent(eventId, "nothing-to-refund"));
        return null;
    }

    private String callProvider(Payment p, String eventId) {
        try {
            return provider.refund(p.getId(), p.getProviderReference(), p.getProviderPaymentId(), p.getAmount(), p.getCurrency()).refundId();
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> payments.findForUpdate(p.getId()).ifPresent(x -> {
                if (x.getStatus() == Payment.Status.REFUNDING) {
                    x.setStatus(Payment.Status.SUCCESS); // release the claim so the retry can run
                }
            }));
            metrics.counter("tickethub.refund.failed").increment();
            log.error("Refund of payment {} failed (event {}): {}", p.getId(), eventId, e.toString());
            throw new TransientProcessingException("Refund failed: " + e.getMessage(), e);
        }
    }
}
