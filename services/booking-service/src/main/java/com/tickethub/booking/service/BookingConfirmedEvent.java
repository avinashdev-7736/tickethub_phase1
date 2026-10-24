package com.tickethub.booking.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Published when payment succeeded and the booking became CONFIRMED. Consumers must tolerate duplicates (eventId). */
public record BookingConfirmedEvent(
        String eventId,
        String eventType,
        String bookingId,
        String customerId,
        String recipientEmail,
        Long showId,
        String eventTitle,
        Instant showStartTime,
        List<String> seats,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {

    public static final String TYPE = "BookingConfirmed";
}
