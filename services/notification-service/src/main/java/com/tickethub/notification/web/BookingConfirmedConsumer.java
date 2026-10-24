package com.tickethub.notification.web;

import com.tickethub.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookingConfirmedConsumer {

    private final NotificationService service;

    public BookingConfirmedConsumer(NotificationService service) {
        this.service = service;
    }

    /** Consumer group means several instances share the partitions; offsets let it catch up after a restart. */
    @KafkaListener(topics = "${tickethub.kafka.topic.booking-confirmed}", groupId = "notification-service")
    public void onMessage(String payload) {
        service.handle(payload);
    }
}
