package com.tickethub.notification.web;

import com.tickethub.common.messaging.MessagingMdc;
import com.tickethub.notification.service.DeadLetterService;
import com.tickethub.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes booking events.
 * - Transient failures (mail provider down) are retried on separate delayed retry topics, so one slow email never blocks the stream.
 * - Anything else (bad data) goes straight to the dead-letter topic ".DLT" and is recorded for inspection and replay.
 * Group id comes from configuration: a replay instance uses a brand new group and so reads from the beginning (scenario 12).
 */
@Component
public class BookingEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingEventsConsumer.class);

    private final NotificationService service;
    private final DeadLetterService deadLetters;

    public BookingEventsConsumer(NotificationService service, DeadLetterService deadLetters) {
        this.service = service;
        this.deadLetters = deadLetters;
    }

    @RetryableTopic(
            attempts = "${notification.retry.attempts:4}",
            backoff = @Backoff(delayExpression = "${notification.retry.delay-ms:2000}",
                    multiplierExpression = "${notification.retry.multiplier:3.0}",
                    maxDelayExpression = "${notification.retry.max-delay-ms:60000}"),
            include = com.tickethub.common.messaging.TransientProcessingException.class,
            dltTopicSuffix = ".DLT",
            retryTopicSuffix = ".retry",
            numPartitions = "3",
            replicationFactor = "1")
    @KafkaListener(topics = "${tickethub.kafka.topic.booking-events}", groupId = "${notification.group-id:notification-service}")
    public void onMessage(@Payload String payload,
                          @Header(name = MessagingMdc.CORRELATION_HEADER, required = false) byte[] correlation) {
        MessagingMdc.run(correlation, null, () -> service.handle(payload));
    }

    @DltHandler
    public void onDeadLetter(@Payload String payload,
                             @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                             @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        log.error("Dead letter received on {}", topic);
        deadLetters.record(payload, topic, error);
    }
}
