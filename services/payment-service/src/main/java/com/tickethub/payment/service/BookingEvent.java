package com.tickethub.payment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Consumer-side view of booking events (tolerant reader: unknown fields are ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEvent(String eventId, String eventType, Integer aggregateVersion, String bookingId, String reason) {

    public static final String REJECTED = "BookingRejected";
    public static final String CANCELLED = "BookingCancelled";
}
