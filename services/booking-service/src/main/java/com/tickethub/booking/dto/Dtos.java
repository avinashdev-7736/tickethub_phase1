package com.tickethub.booking.dto;

import jakarta.validation.constraints.NotBlank;
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
                                  List<SeatInfo> seats, BigDecimal amount, String currency,
                                  Instant expiresAt, Instant createdAt) { }

    public record CreateResult(BookingResponse booking, boolean replay) { }

    // ----- internal -----
    public record ReservedSeat(Long seatId, String state) { }

    public record InternalBooking(String id, String customerId, String status, BigDecimal amount, String currency,
                                  Instant expiresAt, Long showId) { }

    public enum PaymentOutcome { SUCCESS, FAILED }

    public record PaymentResultRequest(@NotBlank String paymentId, @NotNull PaymentOutcome status, String reason) { }

    // ----- response from Event/Show internal API -----
    public record ShowSeat(Long id, String label) { }

    public record ShowInfo(Long showId, String status, String eventTitle, Instant startTime,
                           BigDecimal price, String currency, List<ShowSeat> seats) { }
}
