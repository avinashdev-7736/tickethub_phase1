package com.tickethub.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Retry and dead-letter topics are created by @RetryableTopic; the main topic's retention is explicit so replay limits are known. */
@Configuration
@ConditionalOnProperty(name = "tickethub.kafka.topic.auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopics {

    @Bean
    NewTopic bookingEventsTopic(@Value("${tickethub.kafka.topic.booking-events}") String name,
                                @Value("${tickethub.kafka.topic.retention-ms:604800000}") String retentionMs) {
        return TopicBuilder.name(name).partitions(3).replicas(1).config("retention.ms", retentionMs).build();
    }
}
