package com.tickethub.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 1: publish after the booking commit, best effort. If Kafka is down the booking stays CONFIRMED and
 * the failure is logged and counted (no outbox yet - that is a Phase 2 item).
 */
@Component
public class BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final String topic;

    public BookingEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, MeterRegistry metrics,
                                 @Value("${tickethub.kafka.topic.booking-confirmed}") String topic) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.metrics = metrics;
        this.topic = topic;
    }

    public void publish(BookingConfirmedEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            kafka.send(topic, event.bookingId(), json).whenComplete((result, ex) -> {
                if (ex != null) {
                    failed(event, ex);
                } else {
                    metrics.counter("tickethub.booking.event.published").increment();
                    log.info("Published BookingConfirmed for booking {} (partition {}, offset {})", event.bookingId(),
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            failed(event, e);
        }
    }

    private void failed(BookingConfirmedEvent event, Throwable t) {
        metrics.counter("tickethub.booking.event.publish.failed").increment();
        log.error("FAILED to publish BookingConfirmed for booking {} - booking stays CONFIRMED, notification will not be sent: {}",
                event.bookingId(), t.toString());
    }
}
