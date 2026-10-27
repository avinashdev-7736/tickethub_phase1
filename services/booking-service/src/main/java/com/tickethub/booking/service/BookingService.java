package com.tickethub.booking.service;

import com.tickethub.booking.client.EventClient;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.BookingSeat;
import com.tickethub.booking.domain.SeatReservation;
import com.tickethub.booking.dto.Dtos.*;
import com.tickethub.booking.repo.BookingRepository;
import com.tickethub.booking.repo.SagaLogRepository;
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
    private static final List<String> CATEGORY_RANK = List.of("STANDARD", "PREMIUM", "VIP");

    private final BookingRepository bookings;
    private final SeatReservationRepository reservations;
    private final SagaLogRepository sagaEntries;
    private final EventClient eventClient;
    private final BookingEvents events;
    private final SagaLog saga;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final long expiryMinutes;
    private final int maxSeats;

    public BookingService(BookingRepository bookings, SeatReservationRepository reservations, SagaLogRepository sagaEntries,
                          EventClient eventClient, BookingEvents events, SagaLog saga, MeterRegistry metrics,
                          PlatformTransactionManager tm,
                          @Value("${booking.expiry-minutes:10}") long expiryMinutes,
                          @Value("${booking.max-seats-per-booking:6}") int maxSeats) {
        this.bookings = bookings;
        this.reservations = reservations;
        this.sagaEntries = sagaEntries;
        this.eventClient = eventClient;
        this.events = events;
        this.saga = saga;
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
        Map<Long, ShowSeat> byId = show.seats().stream().collect(Collectors.toMap(ShowSeat::id, s -> s));
        if (!byId.keySet().containsAll(seatIds)) {
            throw BusinessException.badRequest("INVALID_SEATS", "One or more seats do not belong to this show's venue");
        }
        BigDecimal amount = show.price().multiply(BigDecimal.valueOf(seatIds.size())); // server-side price only
        String category = highestCategory(seatIds.stream().map(id -> byId.get(id).type()).toList());

        try {
            BookingResponse created = tx.execute(status -> persistNew(req.showId(), seatIds, byId, show, amount, category,
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

    private static String highestCategory(List<String> types) {
        String best = "STANDARD";
        for (String t : types) {
            if (t != null && CATEGORY_RANK.indexOf(t) > CATEGORY_RANK.indexOf(best)) {
                best = t;
            }
        }
        return best;
    }

    private BookingResponse persistNew(Long showId, List<Long> seatIds, Map<Long, ShowSeat> byId, ShowInfo show,
                                       BigDecimal amount, String category, String customerId, String email, String idemKey) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID().toString());
        b.setCustomerId(customerId);
        b.setCustomerEmail(email);
        b.setShowId(showId);
        b.setEventTitle(show.eventTitle());
        b.setShowStartTime(show.startTime());
        b.setAmount(amount);
        b.setCurrency(show.currency());
        b.setSeatCategory(category);
        b.setStatus(Booking.Status.PENDING_PAYMENT);
        b.setRefundState(Booking.RefundState.NONE);
        b.setExpiresAt(Instant.now().plus(Duration.ofMinutes(expiryMinutes)));
        b.setIdempotencyKey(idemKey);
        for (Long seatId : seatIds) {
            BookingSeat bs = new BookingSeat();
            bs.setBooking(b);
            bs.setSeatId(seatId);
            bs.setSeatLabel(byId.get(seatId).label());
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
        saga.record(b.getId(), null, b.getStatus().name(), "booking created, waiting for payment", null);
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

    /** "What happened to this booking and why?" Owner or ADMIN. */
    public BookingTimeline timeline(String id, String requesterId, boolean admin) {
        return readTx.execute(s -> {
            Booking b = bookings.findById(id).orElseThrow(() -> BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
            if (!admin && !b.getCustomerId().equals(requesterId)) {
                throw BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found");
            }
            List<TimelineEntry> entries = sagaEntries.findByBookingIdOrderByIdAsc(id).stream()
                    .map(e -> new TimelineEntry(e.getAt(), e.getFromStatus(), e.getToStatus(), e.getReason(), e.getEventId())).toList();
            return new BookingTimeline(b.getId(), b.getStatus().name(), b.refundOrNone().name(), b.currentEventSeq(),
                    b.currentPaymentSeq(), entries);
        });
    }

    /**
     * Customer cancel.
     * Unpaid: cancelled immediately. Confirmed: seats are released at once, a refund is requested through BookingCancelled,
     * and the booking becomes CANCELLED when PaymentRefunded arrives.
     */
    public BookingResponse cancel(String id, String customerId) {
        MDC.put("bookingId", id);
        try {
            return tx.execute(s -> {
                Booking b = owned(bookings.findForUpdate(id), customerId);
                Booking.Status from = b.getStatus();
                if (from == Booking.Status.CANCELLED || from == Booking.Status.CANCELLING) {
                    return toResponse(b); // already cancelled: idempotent
                }
                if (from == Booking.Status.PENDING_PAYMENT) {
                    b.setStatus(Booking.Status.CANCELLED);
                    reservations.deleteByBookingId(b.getId());
                    saga.record(b.getId(), from.name(), b.getStatus().name(), "cancelled by customer before payment", null);
                    log.info("Booking {} cancelled by customer; seats released", id);
                    return toResponse(b);
                }
                if (from == Booking.Status.CONFIRMED) {
                    if (!b.getShowStartTime().isAfter(Instant.now())) {
                        throw BusinessException.conflict("SHOW_STARTED", "A booking cannot be cancelled after the show has started");
                    }
                    b.setStatus(Booking.Status.CANCELLING);
                    b.setRefundState(Booking.RefundState.PENDING);
                    reservations.deleteByBookingId(b.getId());
                    String outId = events.cancelled(b, "CUSTOMER_REQUEST");
                    saga.record(b.getId(), from.name(), b.getStatus().name(), "cancelled by customer after payment; refund requested", outId);
                    metrics.counter("tickethub.booking.cancelled.refund").increment();
                    log.info("Booking {} cancelling; refund requested", id);
                    return toResponse(b);
                }
                throw BusinessException.conflict("BOOKING_NOT_CANCELLABLE",
                        "A booking in status " + from + " cannot be cancelled");
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
            saga.record(b.getId(), Booking.Status.PENDING_PAYMENT.name(), b.getStatus().name(), "payment window expired", null);
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

    /** For reconciliation: bookings that became CONFIRMED recently. */
    public List<InternalBooking> confirmedSince(Instant since) {
        return readTx.execute(s -> bookings.findByStatusSince(Booking.Status.CONFIRMED, since).stream().map(this::toInternal).toList());
    }

    // ------------------------------------------------------------------ mapping

    private BookingResponse toResponse(Booking b) {
        List<SeatInfo> seats = b.getSeats().stream()
                .map(s -> new SeatInfo(s.getSeatId(), s.getSeatLabel()))
                .sorted(Comparator.comparing(SeatInfo::label)).toList();
        return new BookingResponse(b.getId(), b.getShowId(), b.getEventTitle(), b.getShowStartTime(), b.getStatus().name(),
                b.refundOrNone().name(), seats, b.getAmount(), b.getCurrency(), b.getSeatCategory(), b.getExpiresAt(), b.getCreatedAt());
    }

    private InternalBooking toInternal(Booking b) {
        return new InternalBooking(b.getId(), b.getCustomerId(), b.getStatus().name(), b.getAmount(), b.getCurrency(),
                b.getExpiresAt(), b.getShowId(), b.refundOrNone().name());
    }
}
