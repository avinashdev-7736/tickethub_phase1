package com.tickethub.booking.config;

import com.tickethub.common.messaging.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {

    /**
     * Poison messages must not block the partition. Transient errors are retried a bounded number of times with growing delay,
     * then the message goes to "<topic>.DLT". Bad data (InvalidEventException) is never retried: straight to the dead-letter topic.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                          @Value("${booking.consumer.retries:4}") int retries,
                                          @Value("${booking.consumer.initial-backoff-ms:500}") long initialMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template); // -> <topic>.DLT, same partition
        ExponentialBackOffWithMaxRetries backoff = new ExponentialBackOffWithMaxRetries(retries);
        backoff.setInitialInterval(initialMs);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(10_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    @Configuration
    @ConditionalOnProperty(name = "tickethub.kafka.topic.auto-create", havingValue = "true", matchIfMissing = true)
    static class Topics {

        @Bean
        NewTopic bookingEventsTopic(@Value("${tickethub.kafka.topic.booking-events}") String name,
                                    @Value("${tickethub.kafka.topic.retention-ms:604800000}") String retentionMs) {
            return TopicBuilder.name(name).partitions(3).replicas(1).config("retention.ms", retentionMs).build();
        }

        @Bean
        NewTopic paymentEventsTopic(@Value("${tickethub.kafka.topic.payment-events}") String name,
                                    @Value("${tickethub.kafka.topic.retention-ms:604800000}") String retentionMs) {
            return TopicBuilder.name(name).partitions(3).replicas(1).config("retention.ms", retentionMs).build();
        }

        @Bean
        NewTopic paymentEventsDlt(@Value("${tickethub.kafka.topic.payment-events}") String name) {
            return TopicBuilder.name(name + ".DLT").partitions(3).replicas(1).build();
        }
    }
}
