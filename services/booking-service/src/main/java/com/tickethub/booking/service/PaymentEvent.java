package com.tickethub.booking.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Consumer-side view of payment events (tolerant reader: unknown fields are ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(
        String eventId,
        String eventType,
        Integer aggregateVersion,
        String bookingId,
        String paymentId,
        BigDecimal amount,
        String currency,
        String reason) {

    public static final String SUCCEEDED = "PaymentSucceeded";
    public static final String FAILED = "PaymentFailed";
    public static final String REFUNDED = "PaymentRefunded";
}
