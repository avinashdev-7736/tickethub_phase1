package com.tickethub.notification.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Consumer-side copy of the event contract (services do not share event classes). Unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
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
}
