package com.tickethub.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() { }

    public record CreateBookingRequest(@NotNull Long showId, @NotEmpty List<@NotNull Long> seatIds) { }

    public record SeatInfo(Long seatId, String label) { }

    public record BookingResponse(String id, Long showId, String eventTitle, Instant showStartTime, String status,
                                  String refundState, List<SeatInfo> seats, BigDecimal amount, String currency,
                                  String seatCategory, Instant expiresAt, Instant createdAt) { }

    public record CreateResult(BookingResponse booking, boolean replay) { }

    /** "What state is booking X in, and why?" */
    public record TimelineEntry(Instant at, String from, String to, String reason, String eventId) { }

    public record BookingTimeline(String bookingId, String status, String refundState, int eventSeq, int lastPaymentSeq,
                                  List<TimelineEntry> entries) { }

    // ----- internal -----
    public record ReservedSeat(Long seatId, String state) { }

    public record InternalBooking(String id, String customerId, String status, BigDecimal amount, String currency,
                                  Instant expiresAt, Long showId, String refundState) { }

    // ----- response from Event/Show internal API -----
    public record ShowSeat(Long id, String label, String type) { }

    public record ShowInfo(Long showId, String status, String eventTitle, Instant startTime,
                           BigDecimal price, String currency, List<ShowSeat> seats) { }
}
