package com.tickethub.booking.service;

import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.BookingSeat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the booking events that go into the outbox. Contract v2 (scenario 13):
 * adds "totalAmount" and "seatCategory". "amount" (the v1 name) is still sent while consumers migrate;
 * switch it off with booking.event.legacy-amount-field=false once every consumer reads "totalAmount".
 */
@Component
public class BookingEvents {

    public static final String CONFIRMED = "BookingConfirmed";
    public static final String REJECTED = "BookingRejected";
    public static final String CANCELLED = "BookingCancelled";

    private final OutboxService outbox;
    private final boolean legacyAmountField;

    public BookingEvents(OutboxService outbox, @Value("${booking.event.legacy-amount-field:true}") boolean legacyAmountField) {
        this.outbox = outbox;
        this.legacyAmountField = legacyAmountField;
    }

    public String confirmed(Booking b) {
        return publish(CONFIRMED, b, null);
    }

    public String rejected(Booking b, String reason) {
        return publish(REJECTED, b, reason);
    }

    public String cancelled(Booking b, String reason) {
        return publish(CANCELLED, b, reason);
    }

    private String publish(String type, Booking b, String reason) {
        int version = b.nextEventSeq();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("customerId", b.getCustomerId());
        p.put("recipientEmail", b.getCustomerEmail());
        p.put("showId", b.getShowId());
        p.put("eventTitle", b.getEventTitle());
        p.put("showStartTime", b.getShowStartTime().toString());
        List<String> labels = b.getSeats().stream().map(BookingSeat::getSeatLabel).sorted().toList();
        p.put("seats", labels);
        p.put("totalAmount", b.getAmount());
        if (legacyAmountField) {
            p.put("amount", b.getAmount());
        }
        p.put("currency", b.getCurrency());
        p.put("seatCategory", b.getSeatCategory());
        if (reason != null) {
            p.put("reason", reason);
        }
        return outbox.add(type, b.getId(), version, p).getEventId();
    }
}
