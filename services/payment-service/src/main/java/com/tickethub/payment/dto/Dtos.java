package com.tickethub.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class Dtos {

    private Dtos() { }

    /** Note: no amount field. The amount always comes from the booking. */
    public record InitiatePaymentRequest(@NotBlank @Size(max = 36) String bookingId) { }

    public record PaymentResponse(String id, String bookingId, BigDecimal amount, String currency, String status,
                                  String provider, String providerReference, String failureReason,
                                  String bookingSync, Instant createdAt) { }

    /** Booking Service internal view. */
    public record InternalBooking(String id, String customerId, String status, BigDecimal amount, String currency,
                                  Instant expiresAt, Long showId) { }
}
