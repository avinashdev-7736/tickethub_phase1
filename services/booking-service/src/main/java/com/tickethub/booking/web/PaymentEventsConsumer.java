package com.tickethub.booking.web;

import com.tickethub.booking.service.BookingSagaService;
import com.tickethub.common.messaging.MessagingMdc;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Booking's saga input: payment events. Errors are handled by the DefaultErrorHandler (bounded retries, then ".DLT"). */
@Component
public class PaymentEventsConsumer {

    private final BookingSagaService saga;

    public PaymentEventsConsumer(BookingSagaService saga) {
        this.saga = saga;
    }

    @KafkaListener(topics = "${tickethub.kafka.topic.payment-events}", groupId = "${booking.group-id:booking-service}")
    public void onMessage(@Payload String payload,
                          @Header(name = MessagingMdc.CORRELATION_HEADER, required = false) byte[] correlation) {
        MessagingMdc.run(correlation, null, () -> saga.handle(payload));
    }
}
