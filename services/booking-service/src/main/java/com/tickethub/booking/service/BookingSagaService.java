package com.tickethub.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.InboxEvent;
import com.tickethub.booking.domain.SeatReservation;
import com.tickethub.booking.repo.BookingRepository;
import com.tickethub.booking.repo.InboxEventRepository;
import com.tickethub.booking.repo.SeatReservationRepository;
import com.tickethub.common.messaging.InvalidEventException;
import com.tickethub.common.messaging.MessagingMdc;
import com.tickethub.common.messaging.TransientProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;

/**
 * Booking's side of the payment saga (choreography): it reacts to payment events and decides what happens to the booking.
 * <ul>
 *   <li>PaymentSucceeded: confirm, OR - if the booking can no longer be confirmed - reject it (BookingRejected -> refund).</li>
 *   <li>PaymentFailed: release the seats.</li>
 *   <li>PaymentRefunded: record the refund; a cancelled booking becomes CANCELLED for good.</li>
 * </ul>
 * Every event is handled once (inbox) and in order (versions); the state change, its follow-up event (outbox) and the inbox row commit together.
 */
@Service
public class BookingSagaService {

    private static final Logger log = LoggerFactory.getLogger(BookingSagaService.class);
    private static final Set<String> SUPPORTED = Set.of(PaymentEvent.SUCCEEDED, PaymentEvent.FAILED, PaymentEvent.REFUNDED);

    private final BookingRepository bookings;
    private final SeatReservationRepository reservations;
    private final InboxEventRepository inbox;
    private final BookingEvents events;
    private final SagaLog saga;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public BookingSagaService(BookingRepository bookings, SeatReservationRepository reservations, InboxEventRepository inbox,
                              BookingEvents events, SagaLog saga, ObjectMapper mapper, MeterRegistry metrics,
                              PlatformTransactionManager tm) {
        this.bookings = bookings;
        this.reservations = reservations;
        this.inbox = inbox;
        this.events = events;
        this.saga = saga;
        this.mapper = mapper;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
    }

    public void handle(String payload) {
        PaymentEvent event = parse(payload);
        if (!SUPPORTED.contains(event.eventType())) {
            return;
        }
        MessagingMdc.run(null, event.bookingId(), () -> process(event));
    }

    private PaymentEvent parse(String payload) {
        try {
            PaymentEvent e = mapper.readValue(payload, PaymentEvent.class);
            if (e == null || e.eventId() == null || e.eventId().isBlank() || e.bookingId() == null || e.bookingId().isBlank()) {
                throw new InvalidEventException("Payment event without eventId or bookingId");
            }
            return e;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new InvalidEventException("Unreadable payment event: " + ex.getMessage(), ex);
        }
    }

    private void process(PaymentEvent event) {
        try {
            tx.executeWithoutResult(s -> applyInTx(event));
        } catch (DataIntegrityViolationException e) {
            if (inbox.existsByEventId(event.eventId())) {
                metrics.counter("tickethub.saga.duplicate").increment();
                log.info("Duplicate {} {} ignored", event.eventType(), event.eventId());
                return;
            }
            throw new TransientProcessingException("Could not record event " + event.eventId(), e);
        }
    }

    private void applyInTx(PaymentEvent ev) {
        inbox.saveAndFlush(new InboxEvent(ev.eventId(), ev.eventType())); // unique: a second copy fails right here
        Booking b = bookings.findForUpdate(ev.bookingId()).orElse(null);
        if (b == null) {
            log.warn("{} refers to unknown booking {}; ignored", ev.eventType(), ev.bookingId());
            return;
        }
        Integer version = ev.aggregateVersion();
        if (version != null) {
            if (version <= b.currentPaymentSeq()) {
                metrics.counter("tickethub.saga.stale").increment();
                log.info("Ignoring stale {} (version {} <= applied {})", ev.eventType(), version, b.currentPaymentSeq());
                return;
            }
            b.setLastPaymentSeq(version);
        }
        switch (ev.eventType()) {
            case PaymentEvent.SUCCEEDED -> onSucceeded(b, ev);
            case PaymentEvent.FAILED -> onFailed(b, ev);
            default -> onRefunded(b, ev);
        }
    }

    private void onSucceeded(Booking b, PaymentEvent ev) {
        Booking.Status from = b.getStatus();
        if (from == Booking.Status.CONFIRMED || from == Booking.Status.REJECTED || from == Booking.Status.CANCELLING) {
            return; // already handled
        }
        if (from == Booking.Status.PENDING_PAYMENT && b.getExpiresAt().isAfter(Instant.now())) {
            b.setStatus(Booking.Status.CONFIRMED);
            reservations.updateState(b.getId(), SeatReservation.State.BOOKED);
            String outId = events.confirmed(b);
            saga.record(b.getId(), from.name(), b.getStatus().name(), "payment " + ev.paymentId() + " succeeded", outId);
            metrics.counter("tickethub.booking.confirmed").increment();
            log.info("Booking {} CONFIRMED (payment {})", b.getId(), ev.paymentId());
            return;
        }
        // Money was taken but the booking cannot be confirmed any more: reject it and give the money back (compensation).
        String reason = switch (from) {
            case PENDING_PAYMENT, EXPIRED -> "BOOKING_EXPIRED";
            case CANCELLED -> "BOOKING_CANCELLED";
            default -> "PAYMENT_AFTER_FAILURE";
        };
        reservations.deleteByBookingId(b.getId());
        b.setStatus(Booking.Status.REJECTED);
        b.setRefundState(Booking.RefundState.PENDING);
        String outId = events.rejected(b, reason);
        saga.record(b.getId(), from.name(), b.getStatus().name(), "payment " + ev.paymentId() + " arrived too late: " + reason, outId);
        metrics.counter("tickethub.booking.rejected").increment();
        log.warn("Booking {} REJECTED ({}); refund requested", b.getId(), reason);
    }

    private void onFailed(Booking b, PaymentEvent ev) {
        if (b.getStatus() != Booking.Status.PENDING_PAYMENT) {
            log.info("PaymentFailed ignored: booking {} is {}", b.getId(), b.getStatus());
            return;
        }
        Booking.Status from = b.getStatus();
        b.setStatus(Booking.Status.PAYMENT_FAILED);
        reservations.deleteByBookingId(b.getId());
        saga.record(b.getId(), from.name(), b.getStatus().name(), "payment failed: " + ev.reason(), null);
        metrics.counter("tickethub.booking.payment.failed").increment();
        log.info("Booking {} PAYMENT_FAILED ({}); seats released", b.getId(), ev.reason());
    }

    private void onRefunded(Booking b, PaymentEvent ev) {
        Booking.Status from = b.getStatus();
        b.setRefundState(Booking.RefundState.REFUNDED);
        if (from == Booking.Status.CANCELLING) {
            b.setStatus(Booking.Status.CANCELLED);
        }
        saga.record(b.getId(), from.name(), b.getStatus().name(), "refund completed (payment " + ev.paymentId() + ")", null);
        metrics.counter("tickethub.booking.refunded").increment();
        log.info("Booking {} refund completed; status {}", b.getId(), b.getStatus());
    }
}
