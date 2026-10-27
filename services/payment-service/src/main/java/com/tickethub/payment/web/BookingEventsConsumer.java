package com.tickethub.payment.web;

import com.tickethub.common.messaging.MessagingMdc;
import com.tickethub.payment.service.RefundService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Payment's saga input: BookingRejected / BookingCancelled mean "refund this booking's payment". */
@Component
public class BookingEventsConsumer {

    private final RefundService refunds;

    public BookingEventsConsumer(RefundService refunds) {
        this.refunds = refunds;
    }

    @KafkaListener(topics = "${tickethub.kafka.topic.booking-events}", groupId = "${payment.group-id:payment-service}")
    public void onMessage(@Payload String payload,
                          @Header(name = MessagingMdc.CORRELATION_HEADER, required = false) byte[] correlation) {
        MessagingMdc.run(correlation, null, () -> refunds.onBookingEvent(payload));
    }
}
