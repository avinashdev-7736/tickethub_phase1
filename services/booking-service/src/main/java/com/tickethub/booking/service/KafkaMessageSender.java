package com.tickethub.booking.service;

import com.tickethub.common.messaging.MessagingMdc;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaMessageSender implements MessageSender {

    private final KafkaTemplate<String, String> kafka;

    public KafkaMessageSender(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void send(String topic, String key, String payload, String correlationId) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        if (correlationId != null) {
            record.headers().add(MessagingMdc.CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).get(5, TimeUnit.SECONDS); // wait for the broker's acknowledgement
    }
}
