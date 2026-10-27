package com.tickethub.payment.config;

import com.tickethub.common.messaging.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
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
     * Bounded retries with growing delay, then the message goes to its own dead-letter topic ("<topic>.payment-service.DLT",
     * separate from Notification's) so a poison message never blocks the partition. Bad data is never retried.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                          @Value("${payment.consumer.retries:4}") int retries,
                                          @Value("${payment.consumer.initial-backoff-ms:500}") long initialMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".payment-service.DLT", record.partition()));
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
        NewTopic paymentEventsTopic(@Value("${tickethub.kafka.topic.payment-events}") String name,
                                    @Value("${tickethub.kafka.topic.retention-ms:604800000}") String retentionMs) {
            return TopicBuilder.name(name).partitions(3).replicas(1).config("retention.ms", retentionMs).build();
        }

        @Bean
        NewTopic bookingEventsTopic(@Value("${tickethub.kafka.topic.booking-events}") String name,
                                    @Value("${tickethub.kafka.topic.retention-ms:604800000}") String retentionMs) {
            return TopicBuilder.name(name).partitions(3).replicas(1).config("retention.ms", retentionMs).build();
        }

        @Bean
        NewTopic bookingEventsPaymentDlt(@Value("${tickethub.kafka.topic.booking-events}") String name) {
            return TopicBuilder.name(name + ".payment-service.DLT").partitions(3).replicas(1).build();
        }
    }
}
