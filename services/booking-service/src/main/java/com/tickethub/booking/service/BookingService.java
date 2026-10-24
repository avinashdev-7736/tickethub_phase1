package com.tickethub.booking.service;

import com.tickethub.booking.client.EventClient;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.BookingSeat;
import com.tickethub.booking.domain.SeatReservation;
import com.tickethub.booking.dto.Dtos.*;
import com.tickethub.booking.repo.BookingRepository;
import com.tickethub.booking.repo.SeatReservationRepository;
import com.tickethub.common.error.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final SeatReservationRepository reservations;
    private final EventClient eventClient;
    private final BookingEventPublisher publisher;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final long expiryMinutes;
    private final int maxSeats;

    public BookingService(BookingRepository bookings, SeatReservationRepository reservations, EventClient eventClient,
                          BookingEventPublisher publisher, MeterRegistry metrics, PlatformTransactionManager tm,
                          @Value("${booking.expiry-minutes:10}") long expiryMinutes,
                          @Value("${booking.max-seats-per-booking:6}") int maxSeats) {
        this.bookings = bookings;
        this.reservations = reservations;
        this.eventClient = eventClient;
        this.publisher = publisher;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.expiryMinutes = expiryMinutes;
        this.maxSeats = maxSeats;
    }

    // ------------------------------------------------------------------ create

    public CreateResult create(CreateBookingRequest req, String customerId, String email, String idempotencyKey) {
        if (email == null || email.isBlank()) {
            throw BusinessException.badRequest("EMAIL_REQUIRED", "The access token has no email claim");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1 to 100 characters");
        }
        List<Long> seatIds = req.seatIds().stream().sorted().toList(); // fixed order lowers deadlock risk
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw BusinessException.badRequest("DUPLICATE_SEATS", "The same seat was selected more than once");
        }
        if (seatIds.size() > maxSeats) {
            throw BusinessException.badRequest("TOO_MANY_SEATS", "At most " + maxSeats + " seats per booking");
        }

        if (idempotencyKey != null) {
            Optional<BookingResponse> replay = findReplay(customerId, idempotencyKey, req.showId(), seatIds);
            if (replay.isPresent()) {
                return new CreateResult(replay.get(), true);
            }
        }

        ShowInfo show = eventClient.getShow(req.showId()); // remote call, outside any DB transaction
        if (!"OPEN".equals(show.status())) {
            throw BusinessException.conflict("SHOW_NOT_BOOKABLE", "This show is not open for booking (status: " + show.status() + ")");
        }
        if (!show.startTime().isAfter(Instant.now())) {
            throw BusinessException.conflict("SHOW_STARTED", "This show has already started");
        }
        Map<Long, String> labels = show.seats().stream().collect(Collectors.toMap(ShowSeat::id, ShowSeat::label));
        if (!labels.keySet().containsAll(seatIds)) {
            throw BusinessException.badRequest("INVALID_SEATS", "One or more seats do not belong to this show's venue");
        }
        BigDecimal amount = show.price().multiply(BigDecimal.valueOf(seatIds.size())); // server-side price only

        try {
            BookingResponse created = tx.execute(status -> persistNew(req.showId(), seatIds, labels, show, amount,
                    customerId, email, idempotencyKey));
            metrics.counter("tickethub.booking.created").increment();
            return new CreateResult(created, false);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            if (idempotencyKey != null) {
                Optional<BookingResponse> replay = findReplay(customerId, idempotencyKey, req.showId(), seatIds);
                if (replay.isPresent()) {
                    return new CreateResult(replay.get(), true);
                }
            }
            metrics.counter("tickethub.booking.seat.conflict").increment();
            log.info("Seat conflict for show {} seats {} (customer {})", req.showId(), seatIds, customerId);
            throw BusinessException.conflict("SEAT_UNAVAILABLE", "One or more selected seats are no longer available");
        }
    }

    private BookingResponse persistNew(Long showId, List<Long> seatIds, Map<Long, String> labels, ShowInfo show,
                                       BigDecimal amount, String customerId, String email, String idemKey) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID().toString());
        b.setCustomerId(customerId);
        b.setCustomerEmail(email);
        b.setShowId(showId);
        b.setEventTitle(show.eventTitle());
        b.setShowStartTime(show.startTime());
        b.setAmount(amount);
        b.setCurrency(show.currency());
        b.setStatus(Booking.Status.PENDING_PAYMENT);
        b.setExpiresAt(Instant.now().plus(Duration.ofMinutes(expiryMinutes)));
        b.setIdempotencyKey(idemKey);
        for (Long seatId : seatIds) {
            BookingSeat bs = new BookingSeat();
            bs.setBooking(b);
            bs.setSeatId(seatId);
            bs.setSeatLabel(labels.get(seatId));
            b.getSeats().add(bs);
        }
        bookings.saveAndFlush(b);

        List<SeatReservation> rows = new ArrayList<>();
        for (Long seatId : seatIds) {
            SeatReservation r = new SeatReservation();
            r.setShowId(showId);
            r.setSeatId(seatId);
            r.setBookingId(b.getId());
            r.setState(SeatReservation.State.RESERVED);
            rows.add(r);
        }
        reservations.saveAllAndFlush(rows); // UNIQUE(show_id, seat_id): exactly one concurrent request wins
        MDC.put("bookingId", b.getId());
        try {
            log.info("Booking {} created for show {}, seats {}, amount {} {}", b.getId(), showId, seatIds, amount, show.currency());
        } finally {
            MDC.remove("bookingId");
        }
        return toResponse(b);
    }

    private Optional<BookingResponse> findReplay(String customerId, String key, Long showId, List<Long> seatIds) {
        return Optional.ofNullable(readTx.execute(s ->
                bookings.findByCustomerIdAndIdempotencyKey(customerId, key).map(existing -> {
                    Set<Long> existingSeats = existing.getSeats().stream().map(BookingSeat::getSeatId).collect(Collectors.toSet());
                    if (!existing.getShowId().equals(showId) || !existingSeats.equals(new HashSet<>(seatIds))) {
                        throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used for a different request");
                    }
                    return toResponse(existing);
                }).orElse(null)));
    }

    // ------------------------------------------------------------------ read / cancel

    public BookingResponse get(String id, String customerId) {
        return readTx.execute(s -> toResponse(owned(bookings.findById(id), customerId)));
    }

    public BookingResponse cancel(String id, String customerId) {
        MDC.put("bookingId", id);
        try {
            return tx.execute(s -> {
                Booking b = owned(bookings.findForUpdate(id), customerId);
                if (b.getStatus() == Booking.Status.CANCELLED) {
                    return toResponse(b); // already cancelled: idempotent
                }
                if (b.getStatus() != Booking.Status.PENDING_PAYMENT) {
                    throw BusinessException.conflict("BOOKING_NOT_CANCELLABLE",
                            "Only an unpaid booking can be cancelled (current status: " + b.getStatus() + ")");
                }
                b.setStatus(Booking.Status.CANCELLED);
                reservations.deleteByBookingId(b.getId());
                log.info("Booking {} cancelled by customer; seats released", id);
                return toResponse(b);
            });
        } finally {
            MDC.remove("bookingId");
        }
    }

    private Booking owned(Optional<Booking> found, String customerId) {
        Booking b = found.orElseThrow(() -> BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        if (!b.getCustomerId().equals(customerId)) { // do not reveal that someone else's booking exists
            throw BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found");
        }
        return b;
    }

    // ------------------------------------------------------------------ payment result (internal)

    private record Outcome(BookingConfirmedEvent event, boolean notPayable, String status, Booking snapshot) { }

    public InternalBooking applyPaymentResult(String id, PaymentResultRequest req) {
        MDC.put("bookingId", id);
        try {
            Outcome out = tx.execute(s -> applyInTx(id, req));
            if (out.event() != null) {
                metrics.counter("tickethub.booking.confirmed").increment();
                publisher.publish(out.event()); // after commit; failure never undoes the booking
            }
            if (out.notPayable()) {
                throw BusinessException.conflict("BOOKING_NOT_PAYABLE",
                        "Booking is " + out.status() + " and can no longer accept this payment result");
            }
            return toInternal(out.snapshot());
        } finally {
            MDC.remove("bookingId");
        }
    }

    private Outcome applyInTx(String id, PaymentResultRequest req) {
        Booking b = bookings.findForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        Booking.Status st = b.getStatus();

        if (req.status() == PaymentOutcome.SUCCESS) {
            if (st == Booking.Status.CONFIRMED) {
                return new Outcome(null, false, st.name(), b); // duplicate callback: idempotent
            }
            if (st == Booking.Status.PENDING_PAYMENT && b.getExpiresAt().isAfter(Instant.now())) {
                b.setStatus(Booking.Status.CONFIRMED);
                reservations.updateState(b.getId(), SeatReservation.State.BOOKED);
                List<String> labels = b.getSeats().stream().map(BookingSeat::getSeatLabel).sorted().toList();
                BookingConfirmedEvent event = new BookingConfirmedEvent(UUID.randomUUID().toString(),
                        BookingConfirmedEvent.TYPE, b.getId(), b.getCustomerId(), b.getCustomerEmail(), b.getShowId(),
                        b.getEventTitle(), b.getShowStartTime(), labels, b.getAmount(), b.getCurrency(), Instant.now());
                log.info("Booking {} CONFIRMED (payment {})", id, req.paymentId());
                return new Outcome(event, false, b.getStatus().name(), b);
            }
            if (st == Booking.Status.PENDING_PAYMENT) { // paid after the hold lapsed: close it properly, report conflict
                b.setStatus(Booking.Status.EXPIRED);
                reservations.deleteByBookingId(b.getId());
                metrics.counter("tickethub.booking.expired").increment();
                log.warn("Payment {} succeeded AFTER booking {} expired - needs refund (Phase 2)", req.paymentId(), id);
            } else {
                log.warn("Payment {} succeeded but booking {} is {} - needs refund (Phase 2)", req.paymentId(), id, st);
            }
            return new Outcome(null, true, b.getStatus().name(), b);
        }

        // FAILED
        if (st == Booking.Status.PENDING_PAYMENT) {
            b.setStatus(Booking.Status.PAYMENT_FAILED);
            reservations.deleteByBookingId(b.getId());
            metrics.counter("tickethub.booking.payment.failed").increment();
            log.info("Booking {} PAYMENT_FAILED ({}); seats released", id, req.reason());
            return new Outcome(null, false, b.getStatus().name(), b);
        }
        if (st == Booking.Status.CONFIRMED) {
            throw BusinessException.conflict("BOOKING_ALREADY_CONFIRMED", "A confirmed booking cannot be marked as payment failed");
        }
        return new Outcome(null, false, st.name(), b); // already closed: nothing to do
    }

    // ------------------------------------------------------------------ expiry (called by the scheduled job)

    /** @return true if this call expired the booking */
    public boolean expireOne(String id) {
        Boolean expired = tx.execute(s -> {
            Booking b = bookings.findForUpdate(id).orElse(null);
            if (b == null || b.getStatus() != Booking.Status.PENDING_PAYMENT || !b.getExpiresAt().isBefore(Instant.now())) {
                return false; // paid/cancelled meanwhile, or another instance got there first
            }
            b.setStatus(Booking.Status.EXPIRED);
            reservations.deleteByBookingId(b.getId());
            return true;
        });
        if (Boolean.TRUE.equals(expired)) {
            metrics.counter("tickethub.booking.expired").increment();
            log.info("Booking {} expired; seats released", id);
        }
        return Boolean.TRUE.equals(expired);
    }

    // ------------------------------------------------------------------ internal reads

    public List<ReservedSeat> reservedSeats(Long showId) {
        return readTx.execute(s -> reservations.findByShowId(showId).stream()
                .map(r -> new ReservedSeat(r.getSeatId(), r.getState().name())).toList());
    }

    public InternalBooking internalGet(String id) {
        return readTx.execute(s -> toInternal(bookings.findById(id)
                .orElseThrow(() -> BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found"))));
    }

    // ------------------------------------------------------------------ mapping

    private BookingResponse toResponse(Booking b) {
        List<SeatInfo> seats = b.getSeats().stream()
                .map(s -> new SeatInfo(s.getSeatId(), s.getSeatLabel()))
                .sorted(Comparator.comparing(SeatInfo::label)).toList();
        return new BookingResponse(b.getId(), b.getShowId(), b.getEventTitle(), b.getShowStartTime(), b.getStatus().name(),
                seats, b.getAmount(), b.getCurrency(), b.getExpiresAt(), b.getCreatedAt());
    }

    private InternalBooking toInternal(Booking b) {
        return new InternalBooking(b.getId(), b.getCustomerId(), b.getStatus().name(), b.getAmount(), b.getCurrency(),
                b.getExpiresAt(), b.getShowId());
    }
}
