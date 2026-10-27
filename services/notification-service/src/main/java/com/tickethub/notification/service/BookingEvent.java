package com.tickethub.notification.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Consumer-side view of every booking event (services do not share event classes).
 * Tolerant reader: unknown fields are ignored, and both contract versions are understood
 * (v1 has "amount"; v2 adds "totalAmount" and "seatCategory", and keeps "amount" while consumers migrate).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEvent(
        String eventId,
        String eventType,
        Integer schemaVersion,
        Integer aggregateVersion,
        String bookingId,
        String customerId,
        String recipientEmail,
        Long showId,
        String eventTitle,
        Instant showStartTime,
        List<String> seats,
        BigDecimal amount,
        BigDecimal totalAmount,
        String currency,
        String seatCategory,
        String reason,
        Instant occurredAt) {

    public static final String CONFIRMED = "BookingConfirmed";
    public static final String CANCELLED = "BookingCancelled";
    public static final String REJECTED = "BookingRejected";

    /** v2 name wins; fall back to the v1 name so old events in the topic still work. */
    public BigDecimal effectiveAmount() {
        return totalAmount != null ? totalAmount : amount;
    }
}
