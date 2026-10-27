package com.tickethub.payment.service;

import com.tickethub.payment.domain.Payment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the payment events that go into the outbox (key = bookingId, version = payment's own counter). */
@Component
public class PaymentEvents {

    public static final String SUCCEEDED = "PaymentSucceeded";
    public static final String FAILED = "PaymentFailed";
    public static final String REFUNDED = "PaymentRefunded";

    private final OutboxService outbox;

    public PaymentEvents(OutboxService outbox) {
        this.outbox = outbox;
    }

    public void succeeded(Payment p) {
        outbox.add(SUCCEEDED, p.getBookingId(), p.nextEventSeq(), base(p));
    }

    /** Reconciliation: tell Booking again about a payment it never heard about. Same version, new event id. */
    public void succeededAgain(Payment p) {
        outbox.add(SUCCEEDED, p.getBookingId(), Math.max(1, p.currentEventSeq()), base(p));
    }

    public void failed(Payment p, String reason) {
        Map<String, Object> m = base(p);
        m.put("reason", reason);
        outbox.add(FAILED, p.getBookingId(), p.nextEventSeq(), m);
    }

    public void refunded(Payment p) {
        Map<String, Object> m = base(p);
        m.put("refundId", p.getRefundId());
        outbox.add(REFUNDED, p.getBookingId(), p.nextEventSeq(), m);
    }

    private Map<String, Object> base(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.getId());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        return m;
    }
}
