package com.tickethub.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "tickethub.kafka.topic.auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    /** Keyed by bookingId, so all events of one booking land in the same partition (ordered). */
    @Bean
    NewTopic bookingConfirmedTopic(@Value("${tickethub.kafka.topic.booking-confirmed}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
